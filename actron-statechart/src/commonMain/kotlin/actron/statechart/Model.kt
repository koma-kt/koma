package actron.statechart

import actron.core.Action
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Stable identifier of a state node in a [StateChartDefinition].
 *
 * @property value The identifier text; must not be blank
 */
sealed interface StateParent {
    /** The implicit root containing the chart's top-level nodes. */
    data object Root : StateParent {
        override fun toString(): String = "root"
    }
}

@JvmInline
value class StateId(
    val value: String,
) : StateParent,
    HistoryFallback {
    init {
        require(value.isNotBlank()) { "[Actron] StateId must not be blank" }
    }

    override fun toString(): String = value
}

/** History either enters an explicit state or uses its parent's initial configuration. */
sealed interface HistoryFallback {
    data object InitialConfiguration : HistoryFallback
}

/**
 * A node in a statechart.
 *
 * The hierarchy is given by [parent] links, so a definition stays a flat list of nodes. More
 * kinds of nodes may be added, so code that matches on this type should expect more cases.
 */
sealed interface StateNode {
    /**
     * The identifier of this node.
     */
    val id: StateId

    /**
     * The compound or parallel node that contains this node, or [StateParent.Root] for a top-level node.
     */
    val parent: StateParent
}

/**
 * A state without child states.
 *
 * @property parent The compound or parallel state that contains this state, or [StateParent.Root] for a
 * top-level state
 */
data class AtomicState(
    override val id: StateId,
    override val parent: StateParent = StateParent.Root,
) : StateNode

/** A terminal leaf; entering it completes its compound parent. It has no outgoing transitions. */
data class FinalState(
    override val id: StateId,
    override val parent: StateParent = StateParent.Root,
) : StateNode

/** External transitions exit/re-enter; internal self-transitions preserve the activation. */
enum class TransitionKind { External, Internal }

/**
 * A state with child states, of which exactly one is active while this state is active.
 *
 * Children are the nodes whose [StateNode.parent] is [id]. Entering this state enters [initial]
 * too, unless a transition targets a deeper descendant directly.
 *
 * @property initial The child entered by default; must be a child of this state
 * @property parent The compound or parallel state that contains this state, or [StateParent.Root] for a
 * top-level state
 */
data class CompoundState(
    override val id: StateId,
    val initial: StateId,
    override val parent: StateParent = StateParent.Root,
) : StateNode

/**
 * A state whose children, its *regions*, are all active while this state is active.
 *
 * Children are the nodes whose [StateNode.parent] is [id]; every child that is not a
 * [HistoryState] is a region. A region may be an [AtomicState] (always active with this state), a
 * [CompoundState] (one active child at a time) or another [ParallelState]. Entering this state
 * enters every region, in declaration order, each through its own initial states unless a
 * transition targets a descendant of it directly. Exiting it exits every region.
 *
 * In one step each active leaf may select a transition, so transitions in different regions fire
 * together for one action (see [StateChartRuntime]). A transition from a descendant of one region
 * into another region leaves this state: its domain is the nearest compound ancestor, so the whole
 * parallel state is exited and entered again. A parallel state should have at least two regions;
 * see [validate].
 *
 * @property parent The compound or parallel state that contains this state, or [StateParent.Root] for a
 * top-level state
 */
data class ParallelState(
    override val id: StateId,
    override val parent: StateParent = StateParent.Root,
) : StateNode

/**
 * A history pseudo-state of the compound or parallel state [parent]. It is never active itself: a transition
 * that targets it enters [parent] (and the ancestors of [parent] it has to) and restores what was
 * active in [parent] when [parent] was last exited.
 *
 * - A shallow history (`deep = false`) remembers the child of [parent] that was active (for a
 *   parallel [parent], all its regions), and restores it; a compound child is entered through its
 *   own initial states.
 * - A deep history (`deep = true`) remembers the active atomic descendants of [parent], and
 *   restores them with every ancestor between them and [parent], outermost first.
 *
 * Until [parent] has been exited once, nothing is remembered, and the transition enters [default],
 * or the initial child of [parent] when [default] is [HistoryFallback.InitialConfiguration] (every region, when [parent] is a
 * [ParallelState]). Regions of a parallel [parent] that the restored nodes do not cover are
 * entered through their initial states. What is remembered lives in
 * [StateConfiguration.history], keyed by this state's [id]; it is recorded right before [parent] is
 * exited, so a transition that exits [parent] and targets this history restores what it just
 * recorded.
 *
 * A history state has no children and no outgoing transitions, and it cannot be the initial state
 * of the chart or of [parent]. A compound state may have several history states, for example one
 * shallow and one deep; each remembers independently. See [validate].
 *
 * @property parent The compound or parallel state whose configuration is remembered
 * @property deep Whether the active atomic descendants are remembered rather than the active child
 * @property default Entered while nothing is remembered: a child of [parent] for a shallow history,
 * any proper descendant of [parent] for a deep one; [HistoryFallback.InitialConfiguration] means the initial child of [parent]
 */
data class HistoryState(
    override val id: StateId,
    override val parent: StateId,
    val deep: Boolean = false,
    val default: HistoryFallback = HistoryFallback.InitialConfiguration,
) : StateNode

/**
 * Describes which action triggers a [Transition].
 *
 * @property name Stable display name used by tools such as the Mermaid exporter. It is also how
 * a [ActionMatching.ByName] matcher recognizes actions (see [matches]).
 * @property matching How actions are recognized
 */
data class ActionMatcher(
    val name: String,
    val matching: ActionMatching = ActionMatching.ByName,
) {
    constructor(name: String, type: KClass<out Action>) : this(name, ActionMatching.ByType(type))

    init {
        require(name.isNotBlank()) { "[Actron] ActionMatcher name must not be blank" }
    }

    /**
     * Whether [action] triggers transitions with this matcher: type matching recognizes
     * subtypes; name matching requires the action's
     * simple class name must equal [name].
     *
     * The runtime and [validate] both use this definition.
     */
    fun matches(action: Action): Boolean = matching.matches(action, name)

    companion object {
        /**
         * Creates a matcher for the action type [A] with the display name [name].
         *
         * The name is explicit rather than taken from the class name, because class names change
         * under code shrinking and obfuscation (R8, ProGuard) and nested classes in different
         * hierarchies can share a simple name. Matching uses the type, not the name.
         *
         * @param name Stable display name; must not be blank
         */
        inline fun <reified A : Action> of(name: String): ActionMatcher = ActionMatcher(name = name, type = A::class)
    }
}

/** The recognition rule of an action matcher. */
sealed interface ActionMatching {
    fun matches(
        action: Action,
        name: String,
    ): Boolean

    data object ByName : ActionMatching {
        override fun matches(
            action: Action,
            name: String,
        ): Boolean = action::class.simpleName == name
    }

    data class ByType(
        val type: KClass<out Action>,
    ) : ActionMatching {
        override fun matches(
            action: Action,
            name: String,
        ): Boolean = type.isInstance(action)
    }
}

/**
 * What makes a [Transition] fire.
 *
 * More kinds of triggers may be added, so code that matches on this type should expect more cases.
 */
sealed interface Trigger {
    /** Fires without external input while its guard holds. */
    data object Eventless : Trigger

    /** Fires when the source compound/parallel state has completed. */
    data object Completion : Trigger

    /**
     * The transition fires when an action matching [matcher] arrives (see [StateChartRuntime.step]).
     */
    data class OnAction(
        val matcher: ActionMatcher,
    ) : Trigger

    /**
     * The transition is a timer: it fires [delay] after its source is entered, unless the source
     * is exited first (see [StateChartRuntime.fire]).
     *
     * The timer starts every time its source is entered and is cancelled every time the source is
     * exited, so re-entering the source (for example through a self-loop) restarts it. The runtime
     * only says which timers to start and cancel ([StepResult.Transitioned.timersToStart] and
     * [StepResult.Transitioned.timersToCancel]); scheduling them is the caller's job.
     *
     * @property delay How long the source must stay active; should be positive (see [validate])
     */
    data class After(
        val delay: Duration,
    ) : Trigger
}

/**
 * A transition from [source] to [target], fired by [trigger]: an action ([Trigger.OnAction]) or a
 * timer ([Trigger.After]).
 *
 * `Transition(source, target, on = matcher, guard)` builds an action transition, as before timers
 * existed; `Transition(source, target, Trigger.After(5.seconds))` builds a timer. Two transitions
 * are equal when all their fields are, so two declarations with the same endpoints, trigger,
 * guard and effect are indistinguishable (see [validate]).
 *
 * @property source The state the transition leaves
 * @property target The state the transition enters
 * @property trigger What fires the transition
 * @property guard Optional label of the condition that must hold; the model only records the label.
 * For a timer, the guard is asked when the timer fires, with [TimerFired] as the action.
 * @property effect Optional label of what the transition does, such as updating data; the model
 * only records the label, and the runtime ignores it
 */
data class Transition(
    val source: StateId,
    val target: StateId,
    val trigger: Trigger,
    val guard: GuardCondition = GuardCondition.Unconditional,
    val effect: TransitionEffect = TransitionEffect.NoEffect,
    val kind: TransitionKind = TransitionKind.External,
) {
    init {
        require(kind != TransitionKind.Internal || source == target) { "[Actron] Internal transitions must target their source" }
    }

    /**
     * A transition fired by an action matching [on].
     */
    constructor(
        source: StateId,
        target: StateId,
        on: ActionMatcher,
        guard: GuardCondition = GuardCondition.Unconditional,
        effect: TransitionEffect = TransitionEffect.NoEffect,
        kind: TransitionKind = TransitionKind.External,
    ) : this(source, target, Trigger.OnAction(on), guard, effect, kind)

    /** Whether this action activates the transition's declared trigger. */
    fun matchesAction(action: Action): Boolean =
        when (val declared = trigger) {
            is Trigger.OnAction -> declared.matcher.matches(action)
            else -> false
        }

    /** Visits the declared action matcher; other triggers perform no action-matcher operation. */
    fun withActionMatcher(visit: (ActionMatcher) -> Unit) {
        val declared = trigger
        if (declared is Trigger.OnAction) visit(declared.matcher)
    }

    /** Delay of a timer transition; callers must first establish [isTimer]. */
    fun timerDelay(): Duration {
        val declared = trigger
        require(declared is Trigger.After) { "[Actron] Not a timer: $this" }
        return declared.delay
    }

    /** Display label of the declared trigger. */
    val triggerLabel: String get() =
        when (val declared = trigger) {
            is Trigger.OnAction -> declared.matcher.name
            else -> declared.toString()
        }

    /**
     * Whether this transition is a timer ([Trigger.After]).
     */
    val isTimer: Boolean get() = trigger is Trigger.After
}

/** Whether edges declare behavior or only bound dynamically chosen destinations. */
enum class TransitionStructure {
    Explicit,
    Conservative,
}

/**
 * Immutable description of a statechart: its states, its initial state and its transitions.
 *
 * The definition is plain data. It does not run anything, so tools can read it fully:
 * see [validate] and [toMermaid].
 *
 * Nodes form a tree through [StateNode.parent]. Top-level nodes behave as children of an implicit
 * root. When an id is declared more than once, the hierarchy helpers below use its first
 * declaration; [validate] reports the duplicate.
 *
 * @property initial The state the chart starts in. Usually a top-level node; a nested one is
 * entered together with its ancestors. A compound one is entered together with its initial
 * descendants. It must not be a [HistoryState].
 * @property states All state nodes, in declaration order
 * @property transitions All transitions, in declaration order
 */
data class StateChartDefinition(
    val initial: StateId,
    val states: List<StateNode>,
    val transitions: List<Transition>,
    val transitionStructure: TransitionStructure = TransitionStructure.Explicit,
) {
    /** Cached static diagnostics, evaluated automatically when inspected. No application code is executed. */
    val diagnostics: List<GraphDiagnostic> by lazy { diagnose() }

    internal val hierarchy: HierarchyIndex by lazy { HierarchyIndex(this) }

    /** The configuration search behind paths and reachability; computed once per definition. */
    internal val configurationGraph: ConfigurationGraph by lazy { buildConfigurationGraph() }

    /**
     * Returns the transitions that leave [source], in declaration order.
     */
    fun transitionsFrom(source: StateId): List<Transition> = hierarchy.transitionsBySource[source].orEmpty()

    /**
     * Returns the first declared node with [id]. Check [hasNode] when the id may be undeclared.
     * @throws NoSuchElementException if the id is undeclared
     */
    fun node(id: StateId): StateNode = hierarchy.nodes.getValue(id)

    /** Whether [id] is declared. */
    fun hasNode(id: StateId): Boolean = id in hierarchy.nodes

    /** Tests a declared node; an undeclared id does not satisfy any node predicate. */
    fun nodeSatisfies(
        id: StateId,
        predicate: (StateNode) -> Boolean,
    ): Boolean = hasNode(id) && predicate(node(id))

    /**
     * Returns the nodes whose parent is [parent], in declaration order, history states included;
     * [StateParent.Root] returns the top-level nodes.
     */
    fun childrenOf(parent: StateParent = StateParent.Root): List<StateNode> = hierarchy.children[parent].orEmpty()

    /**
     * Returns the declared ancestors of [id], innermost first, without [id] itself.
     *
     * The walk stops at a parent that is not declared and before an id it has already visited, so
     * it ends even for a malformed hierarchy (see [validate]).
     */
    fun ancestorsOf(id: StateId): List<StateId> = hierarchy.ancestors(id)

    /**
     * Whether [id] is a proper descendant of [ancestor], that is [ancestor] is in [ancestorsOf].
     */
    fun isDescendant(
        id: StateId,
        ancestor: StateId,
    ): Boolean = ancestor in hierarchy.ancestors(id)

    /** Every declared hierarchy lies below the implicit root. */
    fun isDescendant(
        id: StateId,
        ancestor: StateParent,
    ): Boolean =
        when (ancestor) {
            StateParent.Root -> true
            is StateId -> isDescendant(id, ancestor)
        }

    /**
     * Returns the configuration in which [leaf] is the active leaf: [leaf] and its ancestors,
     * outermost first. It maps a Actron state, through its leaf id, back to a chart configuration.
     *
     * Nothing is entered below [leaf], so pass a leaf, not a compound state. Inside a
     * [ParallelState] one leaf does not describe the other regions; use the overload that takes
     * all active leaves.
     */
    fun configurationOf(leaf: StateId): StateConfiguration = StateConfiguration(active = (hierarchy.ancestors(leaf).asReversed() + leaf).toSet())

    /**
     * Returns the configuration whose active leaves are [leaves]: each leaf after its ancestors,
     * outermost first, leaves in the order given. It maps a Actron state of a chart with
     * [ParallelState]s, through its active leaves (one per active region), back to a chart
     * configuration. Nothing is entered below the leaves, and no missing region is filled in.
     */
    fun configurationOf(leaves: Collection<StateId>): StateConfiguration = StateConfiguration(active = leaves.flatMapTo(linkedSetOf()) { hierarchy.ancestors(it).asReversed() + it })
}

/**
 * Lookup tables for one definition, built on first use.
 */
internal class HierarchyIndex(
    definition: StateChartDefinition,
) {
    val nodes: Map<StateId, StateNode> = buildMap { definition.states.forEach { if (it.id !in this) put(it.id, it) } }
    val children: Map<StateParent, List<StateNode>> = nodes.values.groupBy { it.parent }
    val transitionsBySource: Map<StateId, List<Transition>> = definition.transitions.groupBy { it.source }

    /** Timers ([Trigger.After] transitions) by source, in declaration order. */
    val timersBySource: Map<StateId, List<Transition>> = definition.transitions.filter { it.isTimer }.groupBy { it.source }

    /** Regions of each parallel state: its children that are not history states, in declaration order. */
    val regions: Map<StateId, List<StateId>> =
        nodes.values.filterIsInstance<ParallelState>().associate { p ->
            p.id to children[p.id].orEmpty().filter { it !is HistoryState }.map { it.id }
        }

    /** History states by the id of their parent, in declaration order. */
    val histories: Map<StateId, List<HistoryState>> = nodes.values.filterIsInstance<HistoryState>().groupBy { it.parent }

    /** Declaration position of each first-declared id; undeclared ids sort after all of them. */
    val order: Map<StateId, Int> = nodes.keys.withIndex().associate { (i, id) -> id to i }

    /** Ancestors of every declared id, innermost first; computed eagerly so the index is immutable. */
    private val ancestorsById: Map<StateId, List<StateId>> =
        nodes.keys.associateWith { id ->
            val chain = mutableListOf<StateId>()
            var current = nodes.getValue(id).parent
            while (current is StateId && current != id && current !in chain && current in nodes) {
                chain += current
                current = nodes.getValue(current).parent
            }
            chain
        }

    fun ancestors(id: StateId): List<StateId> = ancestorsById[id].orEmpty()
}
