package actron.statechart.machine

import actron.core.State
import actron.statechart.StateChartDefinition
import actron.statechart.StateConfiguration
import actron.statechart.StateId
import actron.statechart.activeLeaves

/**
 * Everything a [Machine] needs to decide the next step, and everything a checkpoint of the
 * machine itself holds: plain immutable data, so it is a Actron [State] and can be saved,
 * compared and replayed.
 *
 * @property definition The machine this snapshot belongs to
 * @property version The version that produced it; another version must not decide on it
 * @property revision The number of accepted decisions so far: 0 before [MachineInput.Start], then
 * one more per [DecisionOutcome.Handled] decision, also when the business data stayed equal. An
 * ignored or failed input does not advance it
 * @property configuration The active nodes and the history of the chart; empty before start
 * @property context The business data, updated only by reducers and hooks inside a decision
 * @property activations The current activation of every active node
 * @property commands The commands registered and not yet completed, cancelled or failed
 * @property timers The timers scheduled and not yet fired or cancelled
 * @property counters The last ids issued, so the next ids are a function of the snapshot
 */
data class MachineSnapshot<C : Any>(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val revision: Long,
    val configuration: StateConfiguration,
    val context: C,
    val activations: Map<StateId, ActivationId> = emptyMap(),
    val commands: Map<CommandId, CommandRecord> = emptyMap(),
    val timers: Map<TimerId, TimerRecord> = emptyMap(),
    val counters: MachineCounters = MachineCounters(),
    /** Unfinished onEnter work, identified by its owning activation. */
    val pendingEntries: Map<StateId, EntryRecord> = emptyMap(),
) : State {
    /**
     * Whether [MachineInput.Start] has been decided.
     */
    val isStarted: Boolean get() = revision > 0

    /**
     * Whether the node [id] is active: an active leaf or an ancestor of one.
     */
    fun isActive(id: StateId): Boolean = id in configuration.active

    /**
     * The active leaves of [definition] in this snapshot, in declaration order.
     */
    fun activeLeaves(definition: StateChartDefinition): List<StateId> = definition.activeLeaves(configuration)
}

/**
 * A registered command as the snapshot remembers it: which activation it belongs to and which
 * lane it runs in. The command itself and its policy live in the [Decision] that registered it
 * and in the executor.
 */
data class CommandRecord(
    val scope: ActivationId,
    val lane: CommandLane = CommandLane.Independent,
)

/**
 * A scheduled timer: the transition it fires, the activation of the source it was scheduled for,
 * and when it is due on the machine's clock.
 */
data class TimerRecord(
    val transition: TransitionId,
    val activation: ActivationId,
    val deadline: MachineTime,
)

/**
 * The last ids the machine issued. Part of the snapshot so a replay issues the same ids.
 */
data class MachineCounters(
    val activations: Long = 0,
    val commands: Long = 0,
    val timers: Long = 0,
    val effects: Long = 0,
)

/**
 * A node with the activation the machine is in it with.
 */
data class Activation(
    val node: StateId,
    val id: ActivationId,
)

/** Identity of pending state-owned work, separate from externally registered commands. */
data class EntryRecord(
    val activation: ActivationId,
    val command: CommandId,
)
