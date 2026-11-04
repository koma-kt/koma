package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AutomaticTransition
import actron.statechart.ChartInitialization
import actron.statechart.GuardCheck
import actron.statechart.HistoryState
import actron.statechart.MicrostepLimitException
import actron.statechart.StateChartDefinition
import actron.statechart.StateChartRuntime
import actron.statechart.StateConfiguration
import actron.statechart.StateId
import actron.statechart.StepResult
import actron.statechart.TimerFired
import actron.statechart.Trigger
import actron.statechart.activeLeaves
import actron.statechart.endpointIssues
import actron.statechart.inEntryOrder
import actron.statechart.initialConfiguration
import actron.statechart.instantTimerCycles
import actron.statechart.isComplete

/**
 * What an enter hook of a [Machine] sees and may do. It runs inside [Machine.decide], so it is a
 * pure function of its inputs: read [context], [input], [action] and [now], assign [context],
 * register [command]s and emit [event]s. No clock, no random, no repository: whatever the hook
 * needs that is not here belongs in the input or the context.
 */
@ActronStoreDsl
interface MachineEnterScope<C : Any, out A : Action, in CMD : Any, in E : Event> {
    /** The node being entered. */
    val node: StateId

    /** The activation this entry creates; commands registered here belong to it. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /**
     * The action of the step: the dispatched action or command result, [TimerFired] for a timer,
     * [CommandFailure] for a failed command, [ChartInitialization] for [MachineInput.Start].
     */
    val action: Action

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /**
     * Registers a command that runs on its own, cancelled when this activation exits.
     */
    fun command(command: CMD): CommandId

    /**
     * Registers a command in [lane] under [policy], cancelled when this activation exits.
     */
    fun command(
        command: CMD,
        lane: LaneId,
        policy: ConcurrencyPolicy,
    ): CommandId

    /**
     * Asks the executor to deliver [event] after the snapshot is committed.
     */
    fun event(event: E): EffectId
}

/**
 * What an exit hook of a [Machine] sees and may do; pure like [MachineEnterScope].
 */
@ActronStoreDsl
interface MachineExitScope<C : Any, out A : Action, in E : Event> {
    /** The node being exited. */
    val node: StateId

    /** The activation that ends. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /** The action of the step; see [MachineEnterScope.action]. */
    val action: Action

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /**
     * Asks the executor to deliver [event] after the snapshot is committed.
     */
    fun event(event: E): EffectId
}

/**
 * What an action handler of a [Machine] sees and may do; pure like [MachineEnterScope]. It runs
 * for an action that no transition takes while [node] is active: the configuration stays, the
 * activation stays, and commands registered here belong to that activation.
 */
@ActronStoreDsl
interface MachineActionScope<C : Any, out A : Action, in CMD : Any, in E : Event> {
    /** The node whose handler runs. */
    val node: StateId

    /** The current activation of [node]; commands registered here belong to it. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /** The action being handled: the dispatched action, a command result or [CommandFailure]. */
    val action: Action

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /** Registers a command that runs on its own, cancelled when [activation] exits. */
    fun command(command: CMD): CommandId

    /** Registers a command in [lane] under [policy], cancelled when [activation] exits. */
    fun command(
        command: CMD,
        lane: LaneId,
        policy: ConcurrencyPolicy,
    ): CommandId

    /** Asks the executor to deliver [event] after the snapshot is committed. */
    fun event(event: E): EffectId
}

/**
 * Builder of a [Machine]: the implementations of the chart's guard and effect labels, the enter
 * and exit rules of its nodes and the action handlers of its nodes, all pure.
 */
@ActronStoreDsl
class MachineBuilder<C : Any, A : Action, CMD : Any, E : Event> internal constructor() {
    internal val invariants = mutableMapOf<String, MachineInvariant<C>>()
    internal var enforceInvariants = false
    internal var maxMicrosteps = 100

    /** Sets the maximum automatic microsteps of one input; overflow rolls the entire decision back. */
    fun maxMicrosteps(limit: Int) {
        require(limit > 0) { "[Actron] Microstep limit must be positive" }
        maxMicrosteps = limit
    }

    /** Adds a named, pure predicate checked on stable snapshots by tests, replay and inspectors. */
    fun invariant(
        name: String,
        predicate: (MachineSnapshot<C>) -> Boolean,
    ) {
        require(name !in invariants) { "[Actron] Invariant '$name' is declared twice" }
        invariants[name] = MachineInvariant(name, predicate)
    }

    /** Opts into runtime checks: violations fail and roll back the entire decision before IO. */
    fun enforceInvariants() {
        enforceInvariants = true
    }

    internal val guards = mutableMapOf<String, (MachineSnapshot<C>, Action) -> Boolean>()
    internal val guardRejections = mutableMapOf<String, String>()
    internal val effects = mutableMapOf<String, (C, Action) -> C>()
    internal val entries = mutableMapOf<StateId, MutableList<MachineEnterScope<C, A, CMD, E>.() -> Unit>>()
    internal val activities = mutableMapOf<StateId, suspend (EntryExecution<C, A, CMD>) -> Unit>()
    internal val entryTransitions = mutableMapOf<StateId, (Any) -> EntryDestination<C>>()
    internal val exits = mutableMapOf<StateId, MutableList<MachineExitScope<C, A, E>.() -> Unit>>()
    internal val handlers = mutableMapOf<StateId, MutableList<ActionHandler<C, A, CMD, E>>>()

    internal class ActionHandler<C : Any, A : Action, CMD : Any, E : Event>(
        val matcher: ActionMatcher,
        val rule: MachineActionScope<C, A, CMD, E>.() -> Unit,
    )

    /**
     * Implements the guard [label]: a pure predicate over the snapshot before the step and the
     * action ([TimerFired] for a timer, [CommandFailure] for a failed command).
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun guard(
        label: String,
        guard: (snapshot: MachineSnapshot<C>, action: Action) -> Boolean,
    ) {
        require(label !in guards) { "[Actron] Guard '$label' is implemented twice" }
        guards[label] = guard
    }

    /** Implements a guard with an application-supplied explanation for its false branch. */
    fun guard(
        label: String,
        rejectionReason: String,
        guard: (MachineSnapshot<C>, Action) -> Boolean,
    ) {
        require(rejectionReason.isNotBlank()) { "[Actron] Guard rejection reason must not be blank" }
        guard(label, guard)
        guardRejections[label] = rejectionReason
    }

    /**
     * Implements the effect [label] of transitions: a pure update of the context, run after the
     * exit hooks and before the enter hooks of the step.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun effect(
        label: String,
        effect: (context: C, action: Action) -> C,
    ) {
        require(label !in effects) { "[Actron] Effect '$label' is implemented twice" }
        effects[label] = effect
    }

    /** Typed implementation key, shared with the model-building DSL. */
    fun guard(
        key: actron.statechart.GuardKey,
        guard: (MachineSnapshot<C>, Action) -> Boolean,
    ) = guard(key.name, guard)

    /** Typed guard key with an application-supplied explanation for its false branch. */
    fun guard(
        key: actron.statechart.GuardKey,
        rejectionReason: String,
        guard: (MachineSnapshot<C>, Action) -> Boolean,
    ) = guard(key.name, rejectionReason, guard)

    /** Typed reducer key, shared with the model-building DSL. */
    fun effect(
        key: actron.statechart.EffectKey,
        effect: (C, Action) -> C,
    ) = effect(key.name, effect)

    /**
     * Adds a rule run whenever [id] is entered, outermost nodes first; rules of one node run in the
     * order added.
     */
    fun onEnter(
        id: StateId,
        rule: MachineEnterScope<C, A, CMD, E>.() -> Unit,
    ) {
        entries.getOrPut(id) { mutableListOf() } += rule
    }

    /**
     * Adds a rule run whenever [id] is exited, innermost nodes first; rules of one node run in the
     * order added.
     */
    fun onExit(
        id: StateId,
        rule: MachineExitScope<C, A, E>.() -> Unit,
    ) {
        exits.getOrPut(id) { mutableListOf() } += rule
    }

    /**
     * Adds a handler for actions matching [matcher] while [id] is active and no transition of the
     * active configuration takes the action: the way to update the context, register a command
     * or emit an event without leaving and re-entering a node (a self-loop would end the node's
     * activation and cancel its commands). Transitions always win. Among handlers, the innermost
     * active node with a matching handler wins, and within a node the first added; one handler
     * runs per input.
     */
    fun onAction(
        id: StateId,
        matcher: ActionMatcher,
        rule: MachineActionScope<C, A, CMD, E>.() -> Unit,
    ) {
        handlers.getOrPut(id) { mutableListOf() } += ActionHandler(matcher, rule)
    }
}

/**
 * Creates a [Machine] for [chart] under [id] and [version].
 *
 * @throws IllegalArgumentException if a guard or effect label used by [chart] has no
 * implementation, if a rule is added for an undeclared node or a [HistoryState], if a label is
 * implemented twice, if the hierarchy of [chart] is malformed or refers to undeclared states, or
 * if timers without a positive delay restart each other in a loop
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine(
    id: DefinitionId,
    version: DefinitionVersion,
    chart: StateChartDefinition,
    builder: MachineBuilder<C, A, CMD, E>.() -> Unit = {},
): Machine<C, A, CMD, E> = Machine(id, version, chart, MachineBuilder<C, A, CMD, E>().apply(builder))

/**
 * A replay-ready statechart: a pure decision function over [MachineSnapshot]s.
 *
 * [decide] takes a snapshot and a [MachineInput] and returns a [Decision]: the next snapshot and,
 * as data, the commands to register, the scopes to cancel, the timers to schedule and cancel and
 * the events to deliver. It runs nothing, reads no clock and keeps no state, so the same snapshot
 * and input always give the same decision, and a recorded run can be decided again. Executing the
 * decision is the executor's job, after it committed the snapshot.
 *
 * Transition selection, exit and entry order, history and parallel regions are those of
 * [StateChartRuntime]. Around it the machine updates the context (exit rules innermost first,
 * transition effects in selection order, enter rules outermost first), gives every entered node a
 * new [ActivationId], scopes the commands of an enter rule to that activation, cancels the
 * commands and timers of exited activations, and schedules the timers of entered nodes at
 * `now + delay`.
 *
 * A step that exits and re-enters a node (a self-loop, a transition into an ancestor) ends its
 * activation and creates a new one: commands of the old one are cancelled, its timers restart.
 * An action that no transition takes may still be handled by an action handler of an active node
 * (see [MachineBuilder.onAction]): the context, commands and events change, the configuration
 * and the activations do not.
 * A [MachineInput.CommandResult] whose command is not registered any more is ignored as stale;
 * that is how a late result of a cancelled load cannot complete a newer one.
 */
class Machine<C : Any, A : Action, CMD : Any, E : Event> internal constructor(
    val id: DefinitionId,
    val version: DefinitionVersion,
    val chart: StateChartDefinition,
    config: MachineBuilder<C, A, CMD, E>,
) {
    internal val activities = config.activities.toMap()
    private val entryTransitions = config.entryTransitions.toMap()

    /** The immutable set of named predicates, in declaration order. */
    val invariants: List<MachineInvariant<C>> = config.invariants.values.toList()
    private val enforceInvariants = config.enforceInvariants
    private val maxMicrosteps = config.maxMicrosteps
    private val hasAutomaticTransitions = chart.transitions.any { it.trigger == Trigger.Eventless || it.trigger == Trigger.Completion }

    /** Whether a started workflow's active top-level state has completed. */
    fun isComplete(snapshot: MachineSnapshot<C>): Boolean =
        snapshot.isStarted &&
            chart.childrenOf().any { chart.isComplete(snapshot.configuration, it.id) }

    /** Checks any stable snapshot, including a restored one; never executes commands. */
    fun checkInvariants(snapshot: MachineSnapshot<C>): List<InvariantViolation> {
        require(snapshot.definition == id && snapshot.version == version) { "[Actron] Snapshot belongs to another machine or version" }
        return invariants.check(snapshot)
    }

    private val guards: Map<String, (MachineSnapshot<C>, Action) -> Boolean> = config.guards.toMap()
    private val guardRejections: Map<String, String> = config.guardRejections.toMap()
    private val effects: Map<String, (C, Action) -> C> = config.effects.toMap()
    private val entries: Map<StateId, List<MachineEnterScope<C, A, CMD, E>.() -> Unit>> = config.entries.mapValues { it.value.toList() }
    private val exits: Map<StateId, List<MachineExitScope<C, A, E>.() -> Unit>> = config.exits.mapValues { it.value.toList() }
    private val handlers: Map<StateId, List<MachineBuilder.ActionHandler<C, A, CMD, E>>> = config.handlers.mapValues { it.value.toList() }

    private val runtime = StateChartRuntime<MachineSnapshot<C>>(chart, { chart.activeLeaves(it.configuration).first() }, guards)

    /** Test-tooling hook: a separate graph with the same immutable rule bindings. Never restore production snapshots into this variant. */
    @actron.core.InternalActronApi
    fun withTestChart(definition: StateChartDefinition): Machine<C, A, CMD, E> {
        val bindings = MachineBuilder<C, A, CMD, E>()
        bindings.invariants.putAll(invariants.associateBy { it.name })
        bindings.enforceInvariants = enforceInvariants
        bindings.maxMicrosteps = maxMicrosteps
        bindings.guards.putAll(guards)
        bindings.guardRejections.putAll(guardRejections)
        bindings.effects.putAll(effects)
        entries.forEach { (node, rules) -> bindings.entries[node] = rules.toMutableList() }
        exits.forEach { (node, rules) -> bindings.exits[node] = rules.toMutableList() }
        handlers.forEach { (node, rules) -> bindings.handlers[node] = rules.toMutableList() }
        return Machine(id, version, definition, bindings)
    }

    /** Application-authored guard metadata; reading it never evaluates the guard. */
    fun guardRejectionReason(label: String): String = guardRejections[label].orEmpty()

    /** Declared action matchers from active transitions and handlers; no guards or rules run. */
    fun declaredActions(snapshot: MachineSnapshot<C>): List<DeclaredAction> {
        require(snapshot.definition == id && snapshot.version == version) { "[Actron] Snapshot belongs to another machine or version" }
        val actions = linkedMapOf<ActionMatcher, MutableList<ActionDeclaration>>()
        for ((index, transition) in chart.transitions.withIndex()) {
            val trigger = transition.trigger
            if (trigger !is Trigger.OnAction) continue
            val matcher = trigger.matcher
            if (transition.source in snapshot.configuration.active) {
                actions.getOrPut(matcher) { mutableListOf() } += ActionDeclaration.Transition(transition.source, TransitionId(index))
            }
        }
        for (node in chart.inEntryOrder(snapshot.configuration.active)) {
            for (handler in handlers[node].orEmpty()) {
                actions.getOrPut(handler.matcher) { mutableListOf() } += ActionDeclaration.Handler(node)
            }
        }
        return actions.map { (matcher, declarations) -> DeclaredAction(matcher, declarations.toList()) }
    }

    /** Selection-only query shared with action availability. No reducers, hooks or handlers run. */
    internal fun selectAction(
        snapshot: MachineSnapshot<C>,
        action: A,
    ): ActionSelection {
        require(snapshot.definition == id && snapshot.version == version) { "[Actron] Snapshot belongs to another machine or version" }
        val trace = SelectionTrace()
        if (!snapshot.isStarted) return ActionSelection.NotStarted(trace.explanation(snapshot.configuration.active, emptyList()))
        trace.candidates +=
            chart.transitions
                .withIndex()
                .filter {
                    it.value.source in snapshot.configuration.active && it.value.matchesAction(action)
                }.map { TransitionId(it.index) }
        return try {
            val result = runtime.stepObserved(snapshot.configuration, snapshot, action, observer(trace))
            if (result is StepResult.Transitioned) {
                trace.selected = result.transitions.map { TransitionId(chart.transitions.indexOf(it)) }
            } else {
                routeHandler(snapshot, action, { node, _ -> trace.handled(node) }, {})
            }
            ActionSelection.Evaluated(trace.explanation(snapshot.configuration.active, trace.selected))
        } catch (error: Exception) {
            ActionSelection.Failed(trace.explanation(snapshot.configuration.active, trace.selected), FailureDescriptor.of(error))
        }
    }

    init {
        val missing = buildList { chart.transitions.forEach { it.effect.withLabel { add(it) } } }.distinct().filter { it !in effects }
        require(missing.isEmpty()) { "[Actron] Missing effect implementations: ${missing.joinToString()}" }
        val declared =
            chart.states
                .filter { it !is HistoryState }
                .map { it.id }
                .toSet()
        val undeclared = (entries.keys + exits.keys + handlers.keys).filter { it !in declared }
        require(undeclared.isEmpty()) { "[Actron] Rules for undeclared states: ${undeclared.joinToString()}" }
        val undeclaredStates = chart.endpointIssues()
        require(undeclaredStates.isEmpty()) { "[Actron] Chart refers to undeclared or duplicate states: ${undeclaredStates.joinToString()}" }
        val loops = chart.instantTimerCycles()
        require(loops.isEmpty()) {
            "[Actron] Timers without a positive delay restart each other forever: " +
                loops.joinToString { cycle -> cycle.joinToString(" -> ") { "${it.source.value} --after ${it.timerDelay()}--> ${it.target.value}" } }
        }
    }

    /** Timers by source, as positions in the chart's transition list, in declaration order. */
    private data class TimerRule(
        val transition: TransitionId,
        val delay: kotlin.time.Duration,
    )

    private val timersBySource: Map<StateId, List<TimerRule>> =
        buildMap<StateId, MutableList<TimerRule>> {
            for ((index, transition) in chart.transitions.withIndex()) {
                val trigger = transition.trigger
                if (trigger is Trigger.After) {
                    getOrPut(transition.source) { mutableListOf() }
                        .add(TimerRule(TransitionId(index), trigger.delay))
                }
            }
        }

    /**
     * The snapshot before the first input: revision 0, nothing active, [context] as given.
     */
    fun initialSnapshot(context: C): MachineSnapshot<C> =
        MachineSnapshot(
            definition = id,
            version = version,
            revision = 0,
            configuration = StateConfiguration(active = emptySet()),
            context = context,
        )

    /**
     * Decides [input] on [snapshot]. Never throws for a failing guard, reducer or rule: that is a
     * [DecisionOutcome.Failed] decision with the snapshot unchanged.
     *
     * @throws IllegalArgumentException if [snapshot] belongs to another machine or version
     */
    fun decide(
        snapshot: MachineSnapshot<C>,
        input: MachineInput<A>,
    ): Decision<C, CMD, E> = decideObserved(snapshot, input, SelectionObservation.Unobserved)

    /** Decides once and captures actual guard results and candidate priority, without extra guard calls. */
    fun decideExplained(
        snapshot: MachineSnapshot<C>,
        input: MachineInput<A>,
    ): ExplainedDecision<C, CMD, E> {
        val trace = SelectionTrace()
        val decision = decideObserved(snapshot, input, trace)
        return ExplainedDecision(decision, trace.explanation(snapshot.configuration.active, trace.selected))
    }

    private fun decideObserved(
        snapshot: MachineSnapshot<C>,
        input: MachineInput<A>,
        trace: SelectionObservation,
    ): Decision<C, CMD, E> {
        require(snapshot.definition == id && snapshot.version == version) {
            "[Actron] Snapshot of ${snapshot.definition} ${snapshot.version} given to machine $id $version"
        }
        return try {
            val first =
                when (input) {
                    is MachineInput.EntryResult -> {
                        entryResult(snapshot = snapshot, input = input, trace = trace)
                    }

                    is MachineInput.Start -> {
                        if (snapshot.isStarted) ignored(snapshot, IgnoreReason.AlreadyStarted) else start(snapshot, input)
                    }

                    is MachineInput.Dispatch -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            else -> step(snapshot, input, input.action, changed = false, trace = trace)
                        }
                    }

                    is MachineInput.BridgeReceived -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            else -> step(snapshot, input, input.action, changed = false, trace = trace)
                        }
                    }

                    is MachineInput.External -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            else -> step(snapshot, input, input.action, changed = false, trace = trace)
                        }
                    }

                    is MachineInput.TimerFired -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            else -> fire(snapshot, input, trace)
                        }
                    }

                    is MachineInput.CommandResult -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                            else -> step(snapshot, input, input.action, changed = false, trace = trace)
                        }
                    }

                    is MachineInput.CommandCompleted -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                            else -> Decision(DecisionOutcome.Handled, snapshot.copy(revision = snapshot.revision + 1, commands = snapshot.commands - input.command))
                        }
                    }

                    is MachineInput.CommandFailed -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                            else -> step(snapshot.copy(commands = snapshot.commands - input.command), input, CommandFailure(input.command, input.failure), changed = true, trace = trace)
                        }
                    }

                    is MachineInput.CommandAbandoned -> {
                        when {
                            !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                            input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                            else -> Decision(DecisionOutcome.Handled, snapshot.copy(revision = snapshot.revision + 1, commands = snapshot.commands - input.command))
                        }
                    }
                }
            val decision = if (first.isHandled && hasAutomaticTransitions) stabilize(snapshot, input, first, trace) else first
            if (enforceInvariants && decision.snapshot.isStarted) {
                val violations = checkInvariants(decision.snapshot)
                if (violations.isNotEmpty()) throw InvariantViolationException(violations)
            }
            decision
        } catch (e: Exception) {
            Decision(DecisionOutcome.Failed(FailureDescriptor.of(e), e), snapshot)
        }
    }

    private fun entryResult(
        snapshot: MachineSnapshot<C>,
        input: MachineInput.EntryResult<A>,
        trace: SelectionObservation,
    ): Decision<C, CMD, E> {
        val node =
            snapshot.pendingEntries.entries
                .firstOrNull { it.value.activation == input.activation }
                ?.key
                ?: return ignored(snapshot, IgnoreReason.StaleCommand)
        return when (val output = input.output) {
            EntryOutput.Completed -> {
                Decision(DecisionOutcome.Handled, snapshot.copy(revision = snapshot.revision + 1, pendingEntries = snapshot.pendingEntries - node))
            }

            is EntryOutput.Dispatch -> {
                step(snapshot, input, output.action, changed = false, trace = trace)
            }

            is EntryOutput.Emit<*> -> {
                val step = Step(snapshot, input, input)
                step.emit(event = output.event as E)
                step.decision(configuration = snapshot.configuration, transitions = emptyList())
            }

            is EntryOutput.Transition<*> -> {
                val destination = entryTransitions.getValue(node)(output.context)
                val step = Step(snapshot, input, input)
                val result = runtime.stepObserved(snapshot.configuration, snapshot, BehaviorTransition(target = destination.target), observer(trace))
                check(result is StepResult.Transitioned)
                step.exit(result.exited)
                step.context = destination.context
                step.enter(result.entered)
                val selected = result.transitions.map { TransitionId(chart.transitions.indexOf(it)) }
                trace.selected(selected)
                step.decision(configuration = result.configuration, transitions = selected)
            }
        }
    }

    private fun ignored(
        snapshot: MachineSnapshot<C>,
        reason: IgnoreReason,
    ): Decision<C, CMD, E> = Decision(DecisionOutcome.Ignored(reason), snapshot)

    private fun start(
        snapshot: MachineSnapshot<C>,
        input: MachineInput.Start,
    ): Decision<C, CMD, E> {
        val configuration = chart.initialConfiguration()
        val entered = chart.inEntryOrder(configuration.active)
        return Step(snapshot, input, action = ChartInitialization)
            .apply {
                enter(entered)
            }.decision(configuration, transitions = emptyList())
    }

    /**
     * Steps [base] with [action]. [changed] says that [base] already differs from the snapshot
     * the input was decided on (a deregistered command), so an action no transition takes is
     * still a handled decision.
     */
    private fun step(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
        action: Action,
        changed: Boolean,
        trace: SelectionObservation,
    ): Decision<C, CMD, E> {
        if (trace.observing) {
            trace.candidates(
                chart.transitions
                    .withIndex()
                    .filter {
                        it.value.source in base.configuration.active && it.value.matchesAction(action)
                    }.map { TransitionId(it.index) },
            )
        }
        return when (val result = runtime.stepObserved(base.configuration, base, action, observer(trace))) {
            StepResult.Ignored -> {
                handle(base, input, action, trace) {
                    if (changed) Decision(DecisionOutcome.Handled, base.copy(revision = base.revision + 1)) else ignored(base, IgnoreReason.NoTransition)
                }
            }

            is StepResult.Transitioned -> {
                if (trace.observing) trace.selected(result.transitions.map { TransitionId(chart.transitions.indexOf(it)) })
                take(base, input, action, result)
            }
        }
    }

    /**
     * Runs the first action handler of the innermost active node that matches [action], if any:
     * the configuration and the activations stay as they are.
     */
    private fun handle(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
        action: Action,
        trace: SelectionObservation,
        unhandled: () -> Decision<C, CMD, E>,
    ): Decision<C, CMD, E> =
        routeHandler(base, action, { node, handler ->
            trace.handled(node)
            val step = Step(base, input, action)
            step.handle(node, handler.rule)
            when (val destination = step.destination) {
                ActionDestination.Stay -> {
                    step.decision(configuration = base.configuration, transitions = emptyList())
                }

                is ActionDestination.Move -> {
                    val result = runtime.stepObserved(base.configuration, base, BehaviorTransition(target = destination.target), observer(trace))
                    check(result is StepResult.Transitioned) { "Requested transition is not part of this machine" }
                    val selected = result.transitions.map { TransitionId(chart.transitions.indexOf(it)) }
                    trace.selected(selected)
                    step.exit(result.exited)
                    step.enter(result.entered)
                    step.decision(configuration = result.configuration, transitions = selected)
                }
            }
        }, unhandled)

    private inline fun <R : Any> routeHandler(
        base: MachineSnapshot<C>,
        action: Action,
        handle: (StateId, MachineBuilder.ActionHandler<C, A, CMD, E>) -> R,
        unhandled: () -> R,
    ): R {
        for (node in chart.inEntryOrder(base.configuration.active).asReversed()) {
            val handler = handlers[node].orEmpty().firstOrNull { it.matcher.matches(action) } ?: continue
            return handle(node, handler)
        }
        return unhandled()
    }

    private fun fire(
        snapshot: MachineSnapshot<C>,
        input: MachineInput.TimerFired,
        trace: SelectionObservation,
    ): Decision<C, CMD, E> {
        val record = snapshot.timers[input.timer] ?: return ignored(snapshot, IgnoreReason.UnknownTimer)
        val timer = chart.transitions[record.transition.index]
        trace.candidate(record.transition)
        // The timer is spent whether or not its guard holds: it fires once per scheduling.
        val spent = snapshot.copy(timers = snapshot.timers - input.timer)
        return when (val result = runtime.fireObserved(snapshot.configuration, snapshot, timer, observer(trace))) {
            StepResult.Ignored -> {
                Decision(DecisionOutcome.Handled, spent.copy(revision = spent.revision + 1), timersCancelled = listOf(input.timer))
            }

            is StepResult.Transitioned -> {
                if (trace.observing) trace.selected(listOf(record.transition))
                take(spent, input, TimerFired(timer), result, alreadyCancelled = listOf(input.timer))
            }
        }
    }

    private fun stabilize(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
        first: Decision<C, CMD, E>,
        trace: SelectionObservation,
    ): Decision<C, CMD, E> {
        val steps = mutableListOf(first)
        val automatic = mutableListOf<actron.statechart.Transition>()
        var current = first.snapshot
        while (true) {
            if (trace.observing) {
                trace.candidates(
                    chart.transitions
                        .withIndex()
                        .filter {
                            it.value.source in current.configuration.active &&
                                (it.value.trigger == Trigger.Eventless || (it.value.trigger == Trigger.Completion && chart.isComplete(current.configuration, it.value.source)))
                        }.map { TransitionId(it.index) },
                )
            }
            val result = runtime.automaticObserved(current.configuration, current, observer(trace))
            if (result !is StepResult.Transitioned) break
            if (steps.size - 1 >= maxMicrosteps) throw MicrostepLimitException(maxMicrosteps, automatic + result.transitions)
            automatic += result.transitions
            if (trace.observing) trace.selected(result.transitions.map { TransitionId(chart.transitions.indexOf(it)) })
            val action = AutomaticTransition(result.transitions.first().trigger == Trigger.Completion)
            val step = take(current, input, action, result)
            steps += step
            current = step.snapshot.copy(revision = base.revision + 1)
        }
        if (steps.size == 1) return first
        return Decision(
            DecisionOutcome.Handled,
            current.copy(revision = base.revision + 1),
            transitions = steps.flatMap { it.transitions },
            exited = steps.flatMap { it.exited },
            entered = steps.flatMap { it.entered },
            commands = steps.flatMap { it.commands }.filter { it.id in current.commands },
            cancelledScopes = steps.flatMap { it.cancelledScopes },
            timersScheduled = steps.flatMap { it.timersScheduled }.filter { it.id in current.timers },
            timersCancelled = steps.flatMap { it.timersCancelled },
            effects = steps.flatMap { it.effects },
        )
    }

    private fun observer(trace: SelectionObservation): (actron.statechart.Transition, GuardCheck) -> Unit {
        if (!trace.observing) return { _, _ -> }
        return { transition, result ->
            val id = TransitionId(chart.transitions.indexOf(transition))
            if (result.allows) trace.enabled(id)
            transition.guard.withLabel { label ->
                trace.guard(id, label, result)
            }
        }
    }

    private fun take(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
        action: Action,
        result: StepResult.Transitioned,
        alreadyCancelled: List<TimerId> = emptyList(),
    ): Decision<C, CMD, E> {
        val step = Step(base, input, action)
        step.exit(result.exited)
        for (transition in result.transitions) {
            transition.effect.withLabel { step.context = effects.getValue(it)(step.context, action) }
        }
        step.enter(result.entered)
        return step.decision(
            configuration = result.configuration,
            transitions = result.transitions.map { TransitionId(chart.transitions.indexOf(it)) },
            alreadyCancelled = alreadyCancelled,
        )
    }

    /**
     * The mutable working set of one decision. Nothing escapes it but the [Decision] it builds.
     */
    private inner class Step(
        val base: MachineSnapshot<C>,
        val input: MachineInput<A>,
        val action: Action,
    ) {
        var context: C = base.context
        var counters: MachineCounters = base.counters
        var destination: ActionDestination = ActionDestination.Stay
        val exited = mutableListOf<Activation>()
        val entered = mutableListOf<Activation>()
        val commands = mutableListOf<CommandRegistration<CMD>>()
        val entryWork = mutableMapOf<StateId, EntryRecord>()
        val timersScheduled = mutableListOf<TimerSchedule>()
        val effects = mutableListOf<EffectEnvelope<E>>()

        fun exit(nodes: List<StateId>) {
            for (node in nodes) {
                val activation = Activation(node, base.activations.getValue(node))
                exited += activation
                for (rule in exits[node].orEmpty()) {
                    val scope = ExitScope(activation)
                    scope.rule()
                    context = scope.context
                }
            }
        }

        fun enter(nodes: List<StateId>) {
            for (node in nodes) {
                val activation = Activation(node, ActivationId(counters.activations + 1))
                counters = counters.copy(activations = activation.id.value)
                entered += activation
                if (node in activities) {
                    counters = counters.copy(commands = counters.commands + 1)
                    entryWork[node] = EntryRecord(activation = activation.id, command = CommandId(value = counters.commands))
                }
                for (rule in entries[node].orEmpty()) {
                    val scope = EnterScope(activation)
                    scope.rule()
                    context = scope.context
                }
                for (timer in timersBySource[node].orEmpty()) {
                    val timerId = TimerId(counters.timers + 1)
                    counters = counters.copy(timers = timerId.value)
                    timersScheduled += TimerSchedule(timerId, timer.transition, activation.id, input.now + timer.delay)
                }
            }
        }

        fun decision(
            configuration: StateConfiguration,
            transitions: List<TransitionId>,
            alreadyCancelled: List<TimerId> = emptyList(),
        ): Decision<C, CMD, E> {
            val exitedIds = exited.map { it.id }.toSet()
            val exitedNodes = exited.map { it.node }.toSet()
            val timersCancelled = alreadyCancelled + base.timers.filter { (_, record) -> record.activation in exitedIds }.keys
            val commandsKept = base.commands.filterValues { it.scope !in exitedIds }
            val snapshot =
                base.copy(
                    revision = base.revision + 1,
                    configuration = configuration,
                    context = context,
                    activations = (base.activations - exitedNodes) + entered.associate { it.node to it.id },
                    commands = commandsKept + commands.associate { it.id to CommandRecord(it.scope, it.lane) },
                    timers = (base.timers - timersCancelled.toSet()) + timersScheduled.associate { it.id to TimerRecord(it.transition, it.activation, it.deadline) },
                    counters = counters,
                    pendingEntries = (base.pendingEntries - exitedNodes) + entryWork,
                )
            return Decision(
                outcome = DecisionOutcome.Handled,
                snapshot = snapshot,
                transitions = transitions,
                exited = exited.toList(),
                entered = entered.toList(),
                commands = commands.toList(),
                cancelledScopes = exited.map { it.id },
                timersScheduled = timersScheduled.toList(),
                timersCancelled = timersCancelled,
                effects = effects.toList(),
            )
        }

        fun handle(
            node: StateId,
            rule: MachineActionScope<C, A, CMD, E>.() -> Unit,
        ) {
            val scope = ActionScope(Activation(node, base.activations.getValue(node)))
            scope.rule()
            context = scope.context
        }

        /** What every scope shares: the activation it belongs to, the input, the clock, the context. */
        abstract inner class Scope(
            private val owner: Activation,
        ) {
            val node: StateId get() = owner.node
            val activation: ActivationId get() = owner.id
            val input: MachineInput<A> get() = this@Step.input
            val now: MachineTime get() = this@Step.input.now
            var context: C = this@Step.context

            fun event(event: E): EffectId = emit(event)

            protected fun register(
                command: CMD,
                lane: CommandLane,
                policy: ConcurrencyPolicy,
            ): CommandId {
                val id = CommandId(counters.commands + 1)
                counters = counters.copy(commands = id.value)
                commands += CommandRegistration(id, command, owner.id, lane, policy)
                return id
            }
        }

        inner class EnterScope(
            owner: Activation,
        ) : Scope(owner),
            MachineEnterScope<C, A, CMD, E> {
            override val action: Action get() = this@Step.action

            override fun command(command: CMD): CommandId = register(command, CommandLane.Independent, ConcurrencyPolicy.Independent)

            override fun command(
                command: CMD,
                lane: LaneId,
                policy: ConcurrencyPolicy,
            ): CommandId = register(command, lane, policy)
        }

        inner class ActionScope(
            owner: Activation,
        ) : Scope(owner),
            MachineActionScope<C, A, CMD, E>,
            TransitionRequestScope {
            override val action: Action get() = this@Step.action

            override fun requestTransition(target: StateId) {
                check(destination == ActionDestination.Stay) { "An action handler can request only one transition" }
                destination = ActionDestination.Move(target = target)
            }

            override fun command(command: CMD): CommandId = register(command, CommandLane.Independent, ConcurrencyPolicy.Independent)

            override fun command(
                command: CMD,
                lane: LaneId,
                policy: ConcurrencyPolicy,
            ): CommandId = register(command, lane, policy)
        }

        inner class ExitScope(
            owner: Activation,
        ) : Scope(owner),
            MachineExitScope<C, A, E> {
            override val action: Action get() = this@Step.action
        }

        fun emit(event: E): EffectId {
            val id = EffectId(counters.effects + 1)
            counters = counters.copy(effects = id.value)
            effects += EffectEnvelope(id, event)
            return id
        }
    }
}
