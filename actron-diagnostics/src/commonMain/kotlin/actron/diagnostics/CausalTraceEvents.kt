package actron.diagnostics

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DurableCommit
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput

/** Builds metadata from actual observations; never evaluates a machine or retains payloads. */
internal class CausalTraceEvents<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
) {
    fun validateObserved(explained: ExplainedDecision<C, CMD, E>) {
        val snapshot = explained.decision.snapshot
        require(snapshot.definition == machine.id && snapshot.version == machine.version) { "[Actron] Trace belongs to another definition/version" }
    }

    fun validatePersisted(commit: DurableCommit<C, CMD, E>) {
        require(commit.checkpoint.snapshot.definition == machine.id && commit.checkpoint.snapshot.version == machine.version)
        require(commit.decisions.all { it.snapshot.definition == machine.id && it.snapshot.version == machine.version && it.outcome !is DecisionOutcome.Failed }) { "[Actron] Persisted trace contains a foreign/failed decision" }
    }

    fun observed(
        input: MachineInput<A>,
        explained: ExplainedDecision<C, CMD, E>,
        origin: TraceOrigin,
        lineage: TraceLineage,
        provenance: TraceProvenance,
    ): RecordedTrace {
        val decision = explained.decision
        val event =
            CausalTraceEvent(
                machine.id,
                machine.version,
                origin,
                lineage,
                input.traceKind(),
                input.now,
                provenance,
                decision.transitions.toList(),
                decision.commands.map { it.id },
                decision.effects.map { it.id },
                TraceAssessment.Decided(explained.explanation.diagnosticSnapshot(), decision.outcome.traceOutcome(), faults(input, decision.outcome)),
            )
        return RecordedTrace(event, decision.timersScheduled.map { it.id })
    }

    fun persisted(
        commit: DurableCommit<C, CMD, E>,
        origin: TraceOrigin,
        lineage: TraceLineage,
        provenance: TraceProvenance,
    ): RecordedTrace {
        val event =
            CausalTraceEvent(
                machine.id,
                machine.version,
                origin,
                lineage,
                "durable-commit",
                commit.checkpoint.now,
                provenance,
                commit.decisions.flatMap { it.transitions },
                commit.decisions.flatMap { it.commands }.map { it.id },
                commit.decisions.flatMap { it.effects }.map { it.id },
                TraceAssessment.Receipt("persisted"),
            )
        return RecordedTrace(event, commit.decisions.flatMap { it.timersScheduled }.map { it.id })
    }

    private fun faults(
        input: MachineInput<A>,
        outcome: DecisionOutcome,
    ): List<TraceFault> =
        buildList {
            if (outcome is DecisionOutcome.Failed) add(TraceFault.of(FailureSource.Decision, outcome.failure))
            if (input is MachineInput.CommandFailed) add(TraceFault.of(FailureSource.Command, input.failure))
        }
}

internal fun MachineInput<*>.traceProvenance(): TraceProvenance =
    when (this) {
        is MachineInput.CommandResult -> TraceProvenance.Command(command)
        is MachineInput.CommandCompleted -> TraceProvenance.Command(command)
        is MachineInput.CommandFailed -> TraceProvenance.Command(command)
        is MachineInput.CommandAbandoned -> TraceProvenance.Command(command)
        is MachineInput.TimerFired -> TraceProvenance.Timer(timer)
        is MachineInput.BridgeReceived -> TraceProvenance.Bridge(message)
        else -> TraceProvenance.Local
    }

private fun MachineInput<*>.traceKind(): String =
    when (this) {
        is MachineInput.EntryResult -> "entry-result"
        is MachineInput.Start -> "start"
        is MachineInput.Dispatch -> "dispatch"
        is MachineInput.BridgeReceived -> "bridge"
        is MachineInput.External -> "external"
        is MachineInput.TimerFired -> "timer"
        is MachineInput.CommandResult -> "command-result"
        is MachineInput.CommandCompleted -> "command-completed"
        is MachineInput.CommandFailed -> "command-failed"
        is MachineInput.CommandAbandoned -> "command-abandoned"
    }

private fun DecisionOutcome.traceOutcome(): String =
    when (this) {
        DecisionOutcome.Handled -> "handled"
        is DecisionOutcome.Ignored -> "ignored:$reason"
        is DecisionOutcome.Failed -> "failed"
    }
