package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.FinalState
import actron.statechart.HistoryState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.StateParent
import actron.statechart.Transition
import actron.statechart.TransitionKind
import actron.statechart.Trigger
import kotlin.reflect.KClass
import kotlin.time.Duration

/** Each root state value is the complete application state and identifies its graph node. */
fun <S : Any, A : Action, CMD : Any, E : Event> typedStateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    build: TypedStateMachineBuilder<S, S, A, CMD, E>.() -> Unit,
): Machine<S, A, CMD, E> =
    TypedStateMachineBuilder<S, S, A, CMD, E>({ it }, { _, state -> state })
        .apply(build)
        .build(id, version, initial)

@ActronStoreDsl
open class TypedStateMachineBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        private val readState: (C) -> S,
        private val writeState: (C, S) -> C,
        private val implementation: MachineBuilder<C, A, CMD, E> = MachineBuilder(),
        private val transitions: MutableList<Transition> = mutableListOf(),
        private val nodes: MutableList<StateNode> = mutableListOf(),
        private val parent: StateParent = StateParent.Root,
        private val prefix: String = "",
        private val registry: TypedStateRegistry<S>,
    ) {
        /** Preserve the original constructor used by previously compiled inline DSL calls. */
        @PublishedApi
        internal constructor(
            readState: (C) -> S,
            writeState: (C, S) -> C,
            implementation: MachineBuilder<C, A, CMD, E> = MachineBuilder(),
            transitions: MutableList<Transition> = mutableListOf(),
            nodes: MutableList<StateNode> = mutableListOf(),
            parent: StateParent = StateParent.Root,
            prefix: String = "",
        ) : this(readState, writeState, implementation, transitions, nodes, parent, prefix, TypedStateRegistry())

        private val states get() = registry.states
        private val references get() = registry.references

        /** A leaf with an entry factory can be entered implicitly by a parent or history. */
        inline fun <reified T : S> state(
            noinline enter: (C) -> T,
            noinline build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit = {},
        ) {
            registerEntry(T::class, enter, false, build)
        }

        inline fun <reified T : S> state(
            value: T,
            noinline build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit = {},
        ) {
            state<T>(enter = { value }, build = build)
        }

        inline fun <reified T : S> final(noinline enter: (C) -> T) {
            registerEntry(T::class, enter, true) {}
        }

        inline fun <reified T : S> final(value: T) {
            final<T> { value }
        }

        /** Compound types identify structure; the context contains the active leaf value. */
        inline fun <reified T : S> compound(
            initial: S,
            noinline build: TypedCompoundMachineBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            registerCompound(T::class, initial, build)
        }

        /** A parallel node is one outer state value with independently bound child regions. */
        inline fun <reified T : S> parallel(
            noinline enter: (C) -> T,
            noinline build: TypedParallelMachineBuilder<C, S, A, CMD, E>.() -> Unit,
        ) = registerParallel(T::class, enter, build)

        inline fun <reified T : S> parallel(
            value: T,
            noinline build: TypedParallelMachineBuilder<C, S, A, CMD, E>.() -> Unit,
        ) = parallel<T>(enter = { value }, build = build)

        @PublishedApi
        internal fun registerParallel(
            type: KClass<out S>,
            enter: (C) -> S,
            build: TypedParallelMachineBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            val id = reference(type)
            require(id !in states) { "State $id is declared twice" }
            states[id] = type
            registry.factories += id
            nodes += ParallelState(id, parent)
            implementation.onEnter(id) { context = this@TypedStateMachineBuilder.writeState(context, enter(context)) }
            TypedParallelMachineBuilder(
                id,
                implementation,
                transitions,
                nodes,
                TypedMachineStateBuilder(this, MachineStateBuilder(id, implementation, transitions), writeState),
            ).apply(build).finish()
        }

        @PublishedApi
        internal fun implicitReference(type: KClass<out S>): StateId = reference(type).also { registry.implicitTargets += it }

        @PublishedApi
        internal fun valueReference(type: KClass<out S>): StateId = reference(type).also { registry.valueTargets += it }

        @PublishedApi
        internal fun historyReference(type: KClass<out S>): StateId =
            reference(type).let {
                registry.historyTargets += it
                StateId("${it.value}.History")
            }

        @PublishedApi
        internal fun registerEntry(
            type: KClass<out S>,
            enter: (C) -> S,
            final: Boolean,
            build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            val id = reference(type)
            require(id !in states) { "State $id is declared twice" }
            states[id] = type
            registry.factories += id
            nodes += if (final) FinalState(id, parent) else AtomicState(id, parent)
            implementation.onEnter(id) { context = this@TypedStateMachineBuilder.writeState(context, enter(context)) }
            TypedMachineStateBuilder(this, MachineStateBuilder(id, implementation, transitions), writeState).apply(build)
        }

        @PublishedApi
        internal fun registerCompound(
            type: KClass<out S>,
            initial: S,
            build: TypedCompoundMachineBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            val id = reference(type)
            require(id !in states) { "State $id is declared twice" }
            states[id] = type
            registry.compounds += id
            nodes += CompoundState(id, initial = reference(initial::class), parent = parent)
            TypedCompoundMachineBuilder(
                readState,
                writeState,
                implementation,
                transitions,
                nodes,
                id,
                prefix,
                registry,
                TypedMachineStateBuilder(this, MachineStateBuilder(id, implementation, transitions), writeState),
            ).apply(build)
        }

        inline fun <reified T : S> state(noinline build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit = {}) {
            register(T::class, build)
        }

        @PublishedApi
        internal fun reference(type: KClass<out S>): StateId {
            val id = StateId(prefix + requireNotNull(type.simpleName) { "State types must have a stable name" })
            val previous = references.getOrElse(id) { type }
            require(previous == type) { "State type names must be unique: $id" }
            references[id] = type
            return id
        }

        @PublishedApi
        internal fun register(
            type: KClass<out S>,
            build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            val id = reference(type)
            require(id !in states) { "State $id is declared twice" }
            states[id] = type
            nodes += AtomicState(id, parent)
            TypedMachineStateBuilder(this, MachineStateBuilder(id, implementation, transitions), writeState).apply(build)
        }

        internal fun build(
            id: DefinitionId,
            version: DefinitionVersion,
            initial: S,
        ): Machine<C, A, CMD, E> {
            val initialId = finish(initial)
            return Machine(id, version, StateChartDefinition(initialId, nodes.toList(), transitions.toList()), implementation)
        }

        internal fun finish(
            initial: S,
            validateBeforeStart: Boolean = true,
        ): StateId {
            val initialId = reference(initial::class)
            require(references.all { (id, type) -> states[id] == type }) { "Every referenced state type must be declared" }
            require(registry.historyTargets.all { it in registry.histories }) { "Every history target needs history() in its compound" }
            require(registry.implicitTargets.all { it in registry.factories || it in registry.compounds }) { "Implicit targets require a state entry factory" }
            require(registry.valueTargets.none { it in registry.factories || it in registry.compounds }) {
                "Targets with entry factories use on<Action, Target>() or transition; do not also supply a target value"
            }
            if (registry.compounds.isNotEmpty()) {
                require(states.keys.all { it in registry.compounds || it in registry.factories }) { "Hierarchical leaves require entry factories (state(value) or state(enter = ...))" }
            }
            val declared = states.filterKeys { it !in registry.compounds }
            implementation.invariant("${prefix}typed-state-matches-graph") { snapshot ->
                when {
                    !snapshot.isStarted -> {
                        !validateBeforeStart || readState(snapshot.context)::class == initial::class
                    }

                    parent is StateId && !snapshot.isActive(parent) -> {
                        true
                    }

                    else -> {
                        val active = snapshot.configuration.active.filter { it in declared }
                        active.size == 1 && declared[active.single()] == readState(snapshot.context)::class
                    }
                }
            }
            implementation.enforceInvariants()
            return initialId
        }
    }

/** Shared type registry for a single typed region, including all nested compounds. */
@PublishedApi
internal class TypedStateRegistry<S : Any> {
    val states = linkedMapOf<StateId, KClass<out S>>()
    val references = linkedMapOf<StateId, KClass<out S>>()
    val factories = mutableSetOf<StateId>()
    val compounds = mutableSetOf<StateId>()
    val implicitTargets = mutableSetOf<StateId>()
    val valueTargets = mutableSetOf<StateId>()
    val histories = mutableSetOf<StateId>()
    val historyTargets = mutableSetOf<StateId>()
}

@ActronStoreDsl
class TypedCompoundMachineBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        readState: (C) -> S,
        writeState: (C, S) -> C,
        implementation: MachineBuilder<C, A, CMD, E>,
        transitions: MutableList<Transition>,
        private val nodes: MutableList<StateNode>,
        private val id: StateId,
        prefix: String,
        private val registry: TypedStateRegistry<S>,
        @PublishedApi internal val handlers: TypedMachineStateBuilder<C, S, A, CMD, E>,
    ) : TypedStateMachineBuilder<C, S, A, CMD, E>(readState, writeState, implementation, transitions, nodes, id, prefix, registry) {
        inline fun <reified T : A, reified Target : S> on() = handlers.on<T, Target>()

        inline fun <reified Target : S> onDone(
            noinline guard: (C) -> Boolean = { true },
        ) = handlers.onDone<Target>(guard)

        /** Parent handlers participate in the normal inner-before-outer transition priority. */
        fun transitions(build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit) {
            handlers.apply(build)
        }

        fun onEnter(block: StateEnterScope<C, A, CMD, E>.() -> Unit) = handlers.onEnter(block)

        fun onExit(block: StateHookScope<C, A, E>.() -> Unit) = handlers.onExit(block)

        /** One history per compound; absent history falls back to the compound's initial child. */
        fun history(deep: Boolean = false) {
            require(registry.histories.add(id)) { "History for $id is declared twice" }
            nodes += HistoryState(StateId("${id.value}.History"), id, deep)
        }
    }

@ActronStoreDsl
class TypedMachineStateBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val owner: TypedStateMachineBuilder<C, S, A, CMD, E>,
        @PublishedApi internal val delegate: MachineStateBuilder<C, A, CMD, E>,
        @PublishedApi internal val writeState: (C, S) -> C,
    ) {
        /** Declare a graph edge; target's inferred return type is its destination. */
        inline fun <reified T : A> on(noinline transition: TransitionRuleBuilder<C, S, T>.() -> Unit) {
            declare<T>(
                trigger = Trigger.OnAction(matcher = ActionMatcher.of<T>(name = requireNotNull(T::class.simpleName))),
                transition = transition,
            )
        }

        /** An automatic transition evaluated during the same decision until the machine settles. */
        fun always(transition: TransitionRuleBuilder<C, S, Unit>.() -> Unit) {
            declaration<Unit>().apply(transition).register(delegate = delegate, trigger = Trigger.Eventless, action = { Unit })
        }

        /** An activation-owned timer; checkpoints preserve its remaining duration. */
        fun after(
            delay: Duration,
            transition: TransitionRuleBuilder<C, S, Unit>.() -> Unit,
        ) {
            declaration<Unit>().apply(transition).register(delegate = delegate, trigger = Trigger.After(delay = delay), action = { Unit })
        }

        @PublishedApi
        internal inline fun <reified T : A> declare(
            trigger: Trigger,
            noinline transition: TransitionRuleBuilder<C, S, T>.() -> Unit,
        ) {
            declaration<T>().apply(transition).register(delegate = delegate, trigger = trigger, action = { it as T })
        }

        @PublishedApi
        internal fun <T : Any> declaration(): TransitionRuleBuilder<C, S, T> = TransitionRuleBuilder(reference = owner::valueReference, applyTarget = writeState)

        /** Bind a typed child to this state's activation, with typed event routes. */
        fun <CC : Any, CA : Action, CCMD : Any, CE : Event> invoke(
            child: TypedInvocation<C, A, CMD, E, CC, CA, CCMD, CE>,
            context: (MachineSnapshot<C>) -> CC,
            toChild: (E, (CA) -> Unit) -> Unit = { _, _ -> },
            toParent: (CE, (A) -> Unit) -> Unit = { _, _ -> },
        ) = child.register(delegate.source, context, toChild, toParent)

        /** Enter a target whose value is supplied by its state entry factory. */
        inline fun <reified T : A, reified Target : S> on() = transition<T, Target>()

        /** Guarded transition to an entry-factory state or compound. */
        inline fun <reified T : A, reified Target : S> transition(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
        ) {
            delegate.on<T>(owner.implicitReference(Target::class), guard)
        }

        inline fun <reified T : A, reified Parent : S> onHistory(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
        ) {
            delegate.on<T>(owner.historyReference(Parent::class), guard)
        }

        inline fun <reified Target : S> alwaysEnter(
            noinline guard: (C) -> Boolean = { true },
        ) {
            delegate.add(
                owner.implicitReference(Target::class),
                Trigger.Eventless,
                TransitionKind.External,
                { context, _ -> guard(context) },
                { context, _ -> context },
            )
        }

        inline fun <reified Target : S> onDone(
            noinline guard: (C) -> Boolean = { true },
        ) {
            delegate.add(
                owner.implicitReference(Target::class),
                Trigger.Completion,
                TransitionKind.External,
                { context, _ -> guard(context) },
                { context, _ -> context },
            )
        }

        inline fun <reified Target : S> afterEnter(
            delay: Duration,
        ) {
            delegate.after(delay, owner.implicitReference(Target::class))
        }

        /** [to] supplies the complete destination value. */
        inline fun <reified T : A, reified Target : S> on(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline to: (C, T) -> Target,
        ) {
            delegate.on<T>(owner.valueReference(Target::class), guard) { context, action ->
                writeState(context, to(context, action))
            }
        }

        inline fun <reified T : A> internal(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline reduce: (C, T) -> C,
        ) = delegate.internal<T>(guard, reduce)

        fun onEnter(block: StateEnterScope<C, A, CMD, E>.() -> Unit) = delegate.onEnter(block)

        fun onExit(block: StateHookScope<C, A, E>.() -> Unit) = delegate.onExit(block)
    }
