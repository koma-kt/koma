package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.machine.CommandLane
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.Decision
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.EntryOutput
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.LaneId
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.RetryBudget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class RecordingWireEncoder<C : Any, A : Action, CMD : Any, E : Event>(
    private val json: Json,
    private val context: KSerializer<C>,
    private val action: KSerializer<A>,
    private val command: KSerializer<CMD>,
    private val event: KSerializer<E>,
) {
    internal fun Recording<C, A, CMD, E>.toWire(): RecordingWire =
        RecordingWire(
            formatVersion = RECORDING_FORMAT_VERSION,
            definition = definition.value,
            version = version.value,
            start = start.toWire(),
            steps = steps.map { it.toWire() },
        )

    private fun MachineSnapshot<C>.toWire(): SnapshotWire =
        SnapshotWire(
            revision = revision,
            active = configuration.active.map { it.value }.sorted(),
            history =
                configuration.history.entries
                    .sortedBy { it.key.value }
                    .associate { (id, ids) -> id.value to ids.map { it.value }.sorted() },
            context = json.encodeToJsonElement(this@RecordingWireEncoder.context, context),
            activations = activations.entries.sortedBy { it.key.value }.associate { (node, id) -> node.value to id.value },
            commands =
                commands.entries.sortedBy { it.key.value }.associate { (id, record) ->
                    id.value.toString() to
                        when (val lane = record.lane) {
                            CommandLane.Independent -> CommandRecordWire(record.scope.value)
                            is LaneId -> CommandRecordWire(record.scope.value, JsonPrimitive(lane.value))
                        }
                },
            timers = timers.entries.sortedBy { it.key.value }.associate { (id, record) -> id.value.toString() to TimerWire(record.transition.index, record.activation.value, record.deadline.sinceStart.toIsoString()) },
            counters = CountersWire(counters.activations, counters.commands, counters.timers, counters.effects),
            pendingEntries = pendingEntries.entries.sortedBy { it.key.value }.associate { (node, entry) -> node.value to EntryRecordWire(activation = entry.activation.value, command = entry.command.value) },
        )

    internal fun ExecutorCheckpoint<C, CMD>.toWire(): CheckpointWire =
        CheckpointWire(
            snapshot = snapshot.toWire(),
            now = now.sinceStart.toIsoString(),
            running =
                lanes.running.values
                    .sortedBy { it.id.value }
                    .map { it.toWire() },
            queued =
                lanes.queued.entries
                    .sortedBy { it.key.value }
                    .associate { (lane, waiting) -> lane.value to waiting.map { it.toWire() } },
            ending = ending.values.sortedBy { it.id.value }.map { it.toWire() },
            effects =
                effects.map { pending ->
                    @Suppress("UNCHECKED_CAST")
                    PendingEffectWire(pending.id.value, json.encodeToJsonElement(event, pending.event as E), pending.policy.toWire(), pending.attempts, pending.handling)
                },
        )

    private fun EffectPolicy.toWire(): EffectPolicyWire =
        when (this) {
            EffectPolicy.Transient -> {
                EffectPolicyWire("transient")
            }

            is EffectPolicy.Retained -> {
                when (val budget = retryBudget) {
                    RetryBudget.Unlimited -> EffectPolicyWire("retained")
                    is RetryBudget.Limited -> EffectPolicyWire("retained", maxAttempts = JsonPrimitive(budget.maxAttempts))
                }
            }

            is EffectPolicy.Latest -> {
                when (val budget = retryBudget) {
                    RetryBudget.Unlimited -> EffectPolicyWire("latest", JsonPrimitive(key))
                    is RetryBudget.Limited -> EffectPolicyWire("latest", JsonPrimitive(key), JsonPrimitive(budget.maxAttempts))
                }
            }
        }

    private fun CommandRegistration<CMD>.toWire(): CommandRegistrationWire =
        when (val assigned = lane) {
            CommandLane.Independent -> CommandRegistrationWire(id.value, json.encodeToJsonElement(this@RecordingWireEncoder.command, command), scope.value)
            is LaneId -> CommandRegistrationWire(id.value, json.encodeToJsonElement(this@RecordingWireEncoder.command, command), scope.value, JsonPrimitive(assigned.value), json.encodeToJsonElement(PolicyWire.serializer(), policy.toWire()))
        }

    internal fun RecordedStep<C, A, CMD, E>.toWire(): StepWire =
        when (this) {
            is RecordedStep.Committed -> StepWire(input.toWire(), committed = json.encodeToJsonElement(DecisionWire.serializer(), decision.toWire()))
            is RecordedStep.Ignored -> StepWire(input.toWire(), ignored = JsonPrimitive(reason.name))
            is RecordedStep.Failed -> StepWire(input.toWire(), failed = json.encodeToJsonElement(FailureWire.serializer(), failure.toWire()))
        }

    private fun MachineInput<A>.toWire(): JsonObject =
        buildJsonObject {
            val input = this@toWire
            put("type", JsonPrimitive(input.wireType))
            put("now", JsonPrimitive(input.now.sinceStart.toIsoString()))
            when (input) {
                is MachineInput.EntryResult -> {
                    put("activation", JsonPrimitive(input.activation.value))
                    putEntryOutput(output = input.output)
                }

                is MachineInput.Start -> {
                    Unit
                }

                is MachineInput.Dispatch -> {
                    put("action", json.encodeToJsonElement(this@RecordingWireEncoder.action, input.action))
                }

                is MachineInput.BridgeReceived -> {
                    put("action", json.encodeToJsonElement(this@RecordingWireEncoder.action, input.action))
                    put("message", json.encodeToJsonElement(MessageWire.serializer(), MessageWire(input.message.from.value, input.message.effect.value)))
                }

                is MachineInput.External -> {
                    put("action", json.encodeToJsonElement(this@RecordingWireEncoder.action, input.action))
                    put("source", JsonPrimitive(input.source.value))
                }

                is MachineInput.TimerFired -> {
                    put("timer", JsonPrimitive(input.timer.value))
                }

                is MachineInput.CommandResult -> {
                    put("action", json.encodeToJsonElement(this@RecordingWireEncoder.action, input.action))
                    put("command", JsonPrimitive(input.command.value))
                }

                is MachineInput.CommandCompleted -> {
                    put("command", JsonPrimitive(input.command.value))
                }

                is MachineInput.CommandFailed -> {
                    put("command", JsonPrimitive(input.command.value))
                    put("failure", json.encodeToJsonElement(FailureWire.serializer(), input.failure.toWire()))
                }

                is MachineInput.CommandAbandoned -> {
                    put("command", JsonPrimitive(input.command.value))
                    put("reason", JsonPrimitive(input.reason.name))
                }
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEntryOutput(output: EntryOutput<A>) {
        when (output) {
            EntryOutput.Completed -> {
                put("entryKind", JsonPrimitive("completed"))
            }

            is EntryOutput.Dispatch -> {
                put("entryKind", JsonPrimitive("dispatch"))
                put("action", json.encodeToJsonElement(this@RecordingWireEncoder.action, output.action))
            }

            is EntryOutput.Transition<*> -> {
                put("entryKind", JsonPrimitive("transition"))
                put("entryContext", json.encodeToJsonElement(this@RecordingWireEncoder.context, output.context as C))
            }

            is EntryOutput.Emit<*> -> {
                put("entryKind", JsonPrimitive("event"))
                put("entryEvent", json.encodeToJsonElement(this@RecordingWireEncoder.event, output.event as E))
            }
        }
    }

    private fun Decision<C, CMD, E>.toWire(): DecisionWire =
        DecisionWire(
            snapshot = snapshot.toWire(),
            transitions = transitions.map { it.index },
            exited = exited.map { ActivationWire(it.node.value, it.id.value) },
            entered = entered.map { ActivationWire(it.node.value, it.id.value) },
            commands = commands.map { it.toWire() },
            cancelledScopes = cancelledScopes.map { it.value },
            timersScheduled = timersScheduled.map { TimerScheduleWire(it.id.value, it.transition.index, it.activation.value, it.deadline.sinceStart.toIsoString()) },
            timersCancelled = timersCancelled.map { it.value },
            effects = effects.map { EffectWire(it.id.value, json.encodeToJsonElement(event, it.event)) },
        )

    private fun ConcurrencyPolicy.toWire(): PolicyWire =
        when (this) {
            ConcurrencyPolicy.Independent -> error("[Actron] Independent commands have no serialized lane policy")
            ConcurrencyPolicy.Latest -> PolicyWire("latest")
            ConcurrencyPolicy.Sequential -> PolicyWire("sequential")
            ConcurrencyPolicy.DropIfRunning -> PolicyWire("dropIfRunning")
            is ConcurrencyPolicy.Parallel -> PolicyWire("parallel", JsonPrimitive(limit))
        }

    private fun FailureDescriptor.toWire(): FailureWire {
        var type: JsonElement = JsonNull
        var message: JsonElement = JsonNull
        var cause: JsonElement = JsonNull
        withType { type = JsonPrimitive(it) }
        withMessage { message = JsonPrimitive(it) }
        withCause { cause = json.encodeToJsonElement(FailureWire.serializer(), it.toWire()) }
        return FailureWire(type, message, cause, suppressed.map { it.toWire() })
    }

    private val MachineInput<A>.wireType: String
        get() =
            when (this) {
                is MachineInput.EntryResult -> "entryResult"
                is MachineInput.Start -> "start"
                is MachineInput.Dispatch -> "dispatch"
                is MachineInput.BridgeReceived -> "bridgeReceived"
                is MachineInput.External -> "external"
                is MachineInput.TimerFired -> "timerFired"
                is MachineInput.CommandResult -> "commandResult"
                is MachineInput.CommandCompleted -> "commandCompleted"
                is MachineInput.CommandFailed -> "commandFailed"
                is MachineInput.CommandAbandoned -> "commandAbandoned"
            }
}
