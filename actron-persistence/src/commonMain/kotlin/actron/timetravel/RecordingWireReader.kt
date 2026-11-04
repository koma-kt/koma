package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.FailureRelation
import actron.observability.StoreInstanceId
import actron.statechart.StateConfiguration
import actron.statechart.StateId
import actron.statechart.machine.AbandonReason
import actron.statechart.machine.Activation
import actron.statechart.machine.ActivationId
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandLane
import actron.statechart.machine.CommandRecord
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EffectEnvelope
import actron.statechart.machine.EffectId
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.EntryOutput
import actron.statechart.machine.EntryRecord
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.LaneId
import actron.statechart.machine.Lanes
import actron.statechart.machine.MachineCounters
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.MessageId
import actron.statechart.machine.PendingEffect
import actron.statechart.machine.RetryBudget
import actron.statechart.machine.SourceId
import actron.statechart.machine.TimerId
import actron.statechart.machine.TimerRecord
import actron.statechart.machine.TimerSchedule
import actron.statechart.machine.TransitionId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal class RecordingWireReader<C : Any, A : Action, CMD : Any, E : Event>(
    private val json: Json,
    private val context: KSerializer<C>,
    private val action: KSerializer<A>,
    private val command: KSerializer<CMD>,
    private val event: KSerializer<E>,
) {
    internal fun readRecording(wire: RecordingWire): Recording<C, A, CMD, E> {
        val definition = DefinitionId(wire.definition)
        val version = DefinitionVersion(wire.version)
        val start = wire.start.toCheckpoint(definition, version, at = "start")
        val steps = wire.steps.mapIndexed { index, step -> step.toStep(definition, version, at = "step $index") }
        return Recording(definition, version, start, steps)
    }

    private fun SnapshotWire.toSnapshot(
        definition: DefinitionId,
        version: DefinitionVersion,
        at: String,
    ): MachineSnapshot<C> =
        MachineSnapshot(
            definition = definition,
            version = version,
            revision = revision,
            configuration =
                StateConfiguration(
                    active = active.map(::StateId).toSet(),
                    history = history.entries.associate { (id, ids) -> StateId(id) to ids.map(::StateId).toSet() },
                ),
            context = decoding("$at: context") { json.decodeFromJsonElement(this@RecordingWireReader.context, context) },
            activations = activations.entries.associate { (node, id) -> StateId(node) to ActivationId(id) },
            commands = commands.entries.associate { (id, record) -> CommandId(id.toLongOrThrow(at)) to CommandRecord(ActivationId(record.scope), if (record.lane == JsonNull) CommandLane.Independent else LaneId(record.lane.text(at, json))) },
            timers = timers.entries.associate { (id, record) -> TimerId(id.toLongOrThrow(at)) to TimerRecord(TransitionId(record.transition), ActivationId(record.activation), MachineTime(record.deadline.toDuration(at))) },
            counters = MachineCounters(counters.activations, counters.commands, counters.timers, counters.effects),
            pendingEntries = pendingEntries.entries.associate { (node, entry) -> StateId(value = node) to EntryRecord(activation = ActivationId(value = entry.activation), command = CommandId(value = entry.command)) },
        )

    internal fun CheckpointWire.toCheckpoint(
        definition: DefinitionId,
        version: DefinitionVersion,
        at: String,
    ): ExecutorCheckpoint<C, CMD> {
        val snapshot = snapshot.toSnapshot(definition, version, "$at: snapshot")
        val now = MachineTime(now.toDuration(at))
        val running = running.mapIndexed { index, registration -> registration.toRegistration("$at: running $index") }
        val queued = queued.entries.associate { (lane, waiting) -> LaneId(lane) to waiting.mapIndexed { index, registration -> registration.toRegistration("$at: queued in $lane $index") } }
        val ending = ending.mapIndexed { index, registration -> registration.toRegistration("$at: ending $index") }
        val effects =
            effects.mapIndexed { index, effect ->
                PendingEffect(EffectId(effect.id), decoding("$at: effect $index") { json.decodeFromJsonElement(event, effect.event) }, effect.policy.toPolicy(at), effect.attempts, effect.handling)
            }
        // The checkpoint checks itself: commands in one place each, and the snapshot's.
        return decoding(at) { ExecutorCheckpoint(snapshot, now, Lanes(running.associateBy { it.id }, queued), ending.associateBy { it.id }, effects) }
    }

    private fun EffectPolicyWire.toPolicy(at: String): EffectPolicy {
        fun budget(): RetryBudget = if (maxAttempts == JsonNull) RetryBudget.Unlimited else RetryBudget.Limited(maxAttempts.integer(at, json))
        return when (kind) {
            "transient" -> EffectPolicy.Transient
            "retained" -> EffectPolicy.Retained(budget())
            "latest" -> EffectPolicy.Latest(key.required("latest policy without a key", at).text(at, json), budget())
            else -> throw DecodeFailure("unknown effect policy '$kind'", at)
        }
    }

    private fun CommandRegistrationWire.toRegistration(at: String): CommandRegistration<CMD> =
        decoding(at) {
            CommandRegistration(
                CommandId(id),
                json.decodeFromJsonElement(this@RecordingWireReader.command, command),
                ActivationId(scope),
                if (lane == JsonNull) CommandLane.Independent else LaneId(lane.text(at, json)),
                if (policy == JsonNull) ConcurrencyPolicy.Independent else json.decodeFromJsonElement(PolicyWire.serializer(), policy).toPolicy(at),
            )
        }

    internal fun StepWire.toStep(
        definition: DefinitionId,
        version: DefinitionVersion,
        at: String,
    ): RecordedStep<C, A, CMD, E> {
        val input = json.decodeFromJsonElement(InputWire.serializer(), this.input).toInput(at, this.input.keys)
        return when {
            committed != JsonNull -> RecordedStep.Committed(input, json.decodeFromJsonElement(DecisionWire.serializer(), committed).toDecision(definition, version, at))
            ignored != JsonNull -> RecordedStep.Ignored(input, decoding("$at: ignore reason") { IgnoreReason.valueOf(ignored.text(at, json)) })
            failed != JsonNull -> RecordedStep.Failed(input, json.decodeFromJsonElement(FailureWire.serializer(), failed).toDescriptor())
            else -> throw DecodeFailure("a step is committed, ignored or failed", at)
        }
    }

    private fun InputWire.toInput(
        at: String,
        fields: Set<String>,
    ): MachineInput<A> {
        val now = MachineTime(this.now.toDuration(at))

        fun command(message: String): CommandId = CommandId(this.command.required(message, at).long(at, json))
        return when (type) {
            "entryResult" -> {
                MachineInput.EntryResult(activation = ActivationId(value = activation.required("entry result without activation", at).long(at, json)), output = readEntryOutput(at = at, json = json, contextSerializer = this@RecordingWireReader.context, eventSerializer = this@RecordingWireReader.event, fields = fields, action = { readAction(fields, "$at: entry action") }), now = now)
            }

            "start" -> {
                MachineInput.Start(now)
            }

            "dispatch" -> {
                MachineInput.Dispatch(readAction(fields, "$at: action"), now)
            }

            "external" -> {
                MachineInput.External(SourceId(source.required("external without a source", at).text(at, json)), readAction(fields, "$at: action"), now)
            }

            "bridgeReceived" -> {
                MachineInput.BridgeReceived(
                    json.decodeFromJsonElement(MessageWire.serializer(), message.required("bridgeReceived without a message", at)).let { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
                    readAction(fields, "$at: action"),
                    now,
                )
            }

            "timerFired" -> {
                MachineInput.TimerFired(TimerId(timer.required("timerFired without a timer", at).long(at, json)), now)
            }

            "commandResult" -> {
                MachineInput.CommandResult(command("commandResult without a command"), readAction(fields, "$at: result"), now)
            }

            "commandCompleted" -> {
                MachineInput.CommandCompleted(command("commandCompleted without a command"), now)
            }

            "commandFailed" -> {
                MachineInput.CommandFailed(
                    command("commandFailed without a command"),
                    json.decodeFromJsonElement(FailureWire.serializer(), failure.required("commandFailed without a failure", at)).toDescriptor(),
                    now,
                )
            }

            "commandAbandoned" -> {
                MachineInput.CommandAbandoned(
                    command("commandAbandoned without a command"),
                    decoding("$at: abandon reason") { AbandonReason.valueOf(reason.required("commandAbandoned without a reason", at).text(at, json)) },
                    now,
                )
            }

            else -> {
                throw DecodeFailure("unknown input type '$type'", at)
            }
        }
    }

    private fun DecisionWire.toDecision(
        definition: DefinitionId,
        version: DefinitionVersion,
        at: String,
    ): Decision<C, CMD, E> =
        Decision(
            outcome = DecisionOutcome.Handled,
            snapshot = snapshot.toSnapshot(definition, version, "$at: snapshot"),
            transitions = transitions.map(::TransitionId),
            exited = exited.map { Activation(StateId(it.node), ActivationId(it.id)) },
            entered = entered.map { Activation(StateId(it.node), ActivationId(it.id)) },
            commands = commands.mapIndexed { index, registration -> registration.toRegistration("$at: command $index") },
            cancelledScopes = cancelledScopes.map(::ActivationId),
            timersScheduled = timersScheduled.map { TimerSchedule(TimerId(it.id), TransitionId(it.transition), ActivationId(it.activation), MachineTime(it.deadline.toDuration(at))) },
            timersCancelled = timersCancelled.map(::TimerId),
            effects = effects.mapIndexed { index, effect -> EffectEnvelope(EffectId(effect.id), decoding("$at: effect $index") { json.decodeFromJsonElement(event, effect.event) }) },
        )

    private fun PolicyWire.toPolicy(at: String): ConcurrencyPolicy =
        when (kind) {
            "latest" -> ConcurrencyPolicy.Latest
            "sequential" -> ConcurrencyPolicy.Sequential
            "dropIfRunning" -> ConcurrencyPolicy.DropIfRunning
            "parallel" -> ConcurrencyPolicy.Parallel(limit.required("parallel policy without a limit", at).integer(at, json))
            else -> throw DecodeFailure("unknown policy '$kind'", at)
        }

    private fun FailureWire.toDescriptor(): FailureDescriptor {
        require(type == JsonNull || (type is JsonPrimitive && type.isString)) { "[Actron] Failure type must be a JSON string" }
        require(message == JsonNull || (message is JsonPrimitive && message.isString)) { "[Actron] Failure message must be a JSON string" }
        val details =
            when {
                type != JsonNull && message != JsonNull -> FailureDetails.Detailed(type.jsonPrimitive.content, message.jsonPrimitive.content)
                type != JsonNull -> FailureDetails.MetadataOnly(type.jsonPrimitive.content)
                message != JsonNull -> FailureDetails.AnonymousMessage(message.jsonPrimitive.content)
                else -> FailureDetails.Redacted
            }
        val related =
            buildList {
                if (cause != JsonNull) add(FailureRelation.Cause(json.decodeFromJsonElement(FailureWire.serializer(), cause).toDescriptor()))
                suppressed.forEach { add(FailureRelation.Suppressed(it.toDescriptor())) }
            }
        return FailureDescriptor(details, related)
    }

    private fun InputWire.readAction(
        fields: Set<String>,
        at: String,
    ): A =
        decoding(at) {
            if ("action" !in fields) throw DecodeFailure("missing action", at)
            // An application's non-null Action serializer can legitimately encode JSON null.
            json.decodeFromJsonElement(this@RecordingWireReader.action, this.action)
        }
}

private fun <C : Any, A : Action, E : Event> InputWire.readEntryOutput(
    at: String,
    json: Json,
    contextSerializer: KSerializer<C>,
    eventSerializer: KSerializer<E>,
    fields: Set<String>,
    action: () -> A,
): EntryOutput<A> {
    // Opaque application payloads may encode JSON null; only an absent field is invalid.
    fun payload(
        name: String,
        value: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.json.JsonElement {
        if (name !in fields) throw DecodeFailure("missing $name", at)
        return value
    }
    return when (entryKind.required("entry result without kind", at).text(at, json)) {
        "completed" -> EntryOutput.Completed
        "dispatch" -> EntryOutput.Dispatch(action = action())
        "transition" -> EntryOutput.Transition(context = json.decodeFromJsonElement(contextSerializer, payload("entryContext", entryContext)))
        "event" -> EntryOutput.Emit(event = json.decodeFromJsonElement(eventSerializer, payload("entryEvent", entryEvent)))
        else -> throw DecodeFailure("unknown entry output", at)
    }
}
