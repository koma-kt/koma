package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.CompoundState
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.Transition

/** Parallel regions nested within a typed state or compound, with state-local parent handlers. */
@ActronStoreDsl
class TypedParallelMachineBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        private val root: StateId,
        private val implementation: MachineBuilder<C, A, CMD, E>,
        private val transitions: MutableList<Transition>,
        private val nodes: MutableList<StateNode>,
        @PublishedApi internal val handlers: TypedMachineStateBuilder<C, S, A, CMD, E>,
    ) {
        private val regions = mutableSetOf<StateId>()

        /** Region type names namespace their state types; no application string key is required. */
        inline fun <reified R : Any, Child : Any> region(
            initial: Child,
            noinline readState: (C) -> Child,
            noinline writeState: (C, Child) -> C,
            noinline build: TypedStateMachineBuilder<C, Child, A, CMD, E>.() -> Unit,
        ) = registerRegion(requireNotNull(R::class.simpleName) { "Region types need a stable name" }, initial, readState, writeState, build)

        @PublishedApi internal fun <Child : Any> registerRegion(
            name: String,
            initial: Child,
            readState: (C) -> Child,
            writeState: (C, Child) -> C,
            build: TypedStateMachineBuilder<C, Child, A, CMD, E>.() -> Unit,
        ) {
            val id = StateId("${root.value}.$name")
            require(regions.add(id)) { "Region types must have unique names: $name" }
            val builder = TypedStateMachineBuilder(readState, writeState, implementation, transitions, nodes, id, "${id.value}.").apply(build)
            nodes += CompoundState(id, initial = builder.finish(initial, validateBeforeStart = false), parent = root)
        }

        inline fun <reified T : A, reified Target : S> on() = handlers.on<T, Target>()

        inline fun <reified Target : S> onDone(
            noinline guard: (C) -> Boolean = { true },
        ) = handlers.onDone<Target>(guard)

        fun transitions(build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit) {
            handlers.apply(build)
        }

        fun onEnter(block: StateEnterScope<C, A, CMD, E>.() -> Unit) = handlers.onEnter(block)

        fun onExit(block: StateHookScope<C, A, E>.() -> Unit) = handlers.onExit(block)

        internal fun finish() {
            require(regions.size >= 2) { "A parallel state needs at least two regions" }
        }
    }
