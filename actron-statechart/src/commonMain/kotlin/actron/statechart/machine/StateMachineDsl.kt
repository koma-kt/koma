package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.EffectKey
import actron.statechart.GuardCondition
import actron.statechart.GuardKey
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.Transition
import actron.statechart.TransitionKind
import actron.statechart.Trigger
import kotlin.time.Duration

/** State-local, typed facade over the same replayable Machine engine.
 * Generated implementation keys are deterministic declaration indices. Bump the definition
 * version when changing the graph, as with the explicit chart API.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> stateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: StateId,
    build: StateMachineBuilder<C, A, CMD, E>.() -> Unit,
): Machine<C, A, CMD, E> = StateMachineBuilder<C, A, CMD, E>().apply(build).build(id, version, initial)

@ActronStoreDsl
class StateMachineBuilder<C : Any, A : Action, CMD : Any, E : Event> internal constructor() {
    private val nodes = mutableListOf<StateNode>()
    private val transitions = mutableListOf<Transition>()
    private val implementation = MachineBuilder<C, A, CMD, E>()

    fun state(
        id: StateId,
        build: MachineStateBuilder<C, A, CMD, E>.() -> Unit,
    ) {
        nodes += AtomicState(id)
        MachineStateBuilder(id, implementation, transitions).apply(build)
    }

    internal fun build(
        id: DefinitionId,
        version: DefinitionVersion,
        initial: StateId,
    ): Machine<C, A, CMD, E> = Machine(id, version, StateChartDefinition(initial, nodes.toList(), transitions.toList()), implementation)
}

@ActronStoreDsl
class MachineStateBuilder<C : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val source: StateId,
        private val implementation: MachineBuilder<C, A, CMD, E>,
        private val transitions: MutableList<Transition>,
    ) {
        internal fun entryActivity(block: suspend ScreenEntryScope<C, A, E>.() -> Unit) {
            require(source !in implementation.activities) { "Only one onEnter per state" }
            implementation.activities[source] = { execution ->
                BehaviorEntryScope<C, A, CMD, E>(execution.context, execution.now, execution.publish, execution.execute).block()
            }
        }

        inline fun <reified T : A> on(
            target: StateId,
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline reduce: (C, T) -> C = { context, _ -> context },
        ) = typed<T>(target, TransitionKind.External, guard, reduce)

        /** Handles an action without restarting timers or cancelling commands. */
        inline fun <reified T : A> internal(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline reduce: (C, T) -> C = { context, _ -> context },
        ) = typed<T>(source, TransitionKind.Internal, guard, reduce)

        @PublishedApi
        internal inline fun <reified T : A> typed(
            target: StateId,
            kind: TransitionKind,
            noinline guard: (C, T) -> Boolean,
            noinline reduce: (C, T) -> C,
        ) {
            add(
                target,
                Trigger.OnAction(ActionMatcher.of<T>(T::class.simpleName ?: "Action")),
                kind,
                { context, action -> action is T && guard(context, action) },
                { context, action ->
                    require(action is T)
                    reduce(context, action)
                },
            )
        }

        fun after(
            delay: Duration,
            target: StateId,
            reduce: (C) -> C = { it },
        ) {
            add(target, Trigger.After(delay), TransitionKind.External, { _, _ -> true }, { context, _ -> reduce(context) })
        }

        fun onEnter(block: MachineEnterScope<C, A, CMD, E>.() -> Unit) = implementation.onEnter(source, block)

        fun onExit(block: MachineExitScope<C, A, E>.() -> Unit) = implementation.onExit(source, block)

        internal fun addDeclared(
            target: StateId,
            trigger: Trigger,
            rule: DeclaredRule<C>,
        ) {
            val key = "dsl.transition.${transitions.size}"
            val guard =
                if (rule.guarded) {
                    implementation.guard(label = key, guard = { snapshot, action -> rule.guard(snapshot.context, action) })
                    GuardKey(name = key)
                } else {
                    GuardCondition.Unconditional
                }
            implementation.effect(label = key, effect = rule.reduce)
            transitions += Transition(source = source, target = target, trigger = trigger, guard = guard, effect = EffectKey(name = key))
        }

        @PublishedApi
        internal fun add(
            target: StateId,
            trigger: Trigger,
            kind: TransitionKind,
            guard: (C, Action) -> Boolean,
            reduce: (C, Action) -> C,
        ) {
            val key = "dsl.transition.${transitions.size}"
            implementation.guard(key) { snapshot, action -> guard(snapshot.context, action) }
            implementation.effect(key, reduce)
            transitions += Transition(source, target, trigger, GuardKey(key), EffectKey(key), kind)
        }
    }
