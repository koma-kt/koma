package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.GuardKey
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import kotlinx.coroutines.ensureActive
import kotlin.reflect.KClass

/** Builds state-local Kotlin handlers on the StateChart decision engine. */
fun <S : Any, A : Action, CMD : Any, E : Event> behaviorStateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    build: BehaviorMachineBuilder<S, S, A, CMD, E>.() -> Unit,
): Machine<S, A, CMD, E> = behaviorStateMachine(id = id, version = version, initial = initial, phaseOf = { it }, build = build)

/** [phaseOf] selects the graph node from the complete immutable application state. */
fun <C : Any, S : Any, A : Action, CMD : Any, E : Event> behaviorStateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    phaseOf: (C) -> S,
    build: BehaviorMachineBuilder<C, S, A, CMD, E>.() -> Unit,
): Machine<C, A, CMD, E> =
    BehaviorMachineBuilder<C, S, A, CMD, E>(phaseOf = phaseOf)
        .apply(block = build)
        .build(id = id, version = version, initial = initial)

@ActronStoreDsl
class BehaviorMachineBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event> internal constructor(
    private val phaseOf: (C) -> S,
) {
    private val states = linkedMapOf<KClass<out S>, StateId>()
    private val implementation = MachineBuilder<C, A, CMD, E>()

    inline fun <reified T : S> state(noinline build: BehaviorStateBuilder<C, A, CMD, E>.() -> Unit = {}) {
        register(type = T::class, build = build)
    }

    @PublishedApi
    internal fun register(
        type: KClass<out S>,
        build: BehaviorStateBuilder<C, A, CMD, E>.() -> Unit,
    ) {
        val id = StateId(value = requireNotNull(value = type.simpleName) { "State types need stable names" })
        require(value = type !in states && id !in states.values) { "State $id is declared twice" }
        states[type] = id
        BehaviorStateBuilder(source = id, implementation = implementation, target = ::target).apply(block = build)
    }

    private fun target(state: C): StateId = states.getValue(key = phaseOf(state)::class)

    internal fun build(
        id: DefinitionId,
        version: DefinitionVersion,
        initial: S,
    ): Machine<C, A, CMD, E> {
        // Kotlin handlers choose their target at execution time. These guarded edges are the
        // conservative structural envelope, not a claim that every edge is reachable.
        val transitions =
            states.values.flatMap { source ->
                states.values.map { target ->
                    val key = "behavior.${source.value}.${target.value}"
                    implementation.guard(label = key, guard = { _, action -> action is BehaviorTransition && action.target == target })
                    Transition(source = source, target = target, trigger = Trigger.OnAction(matcher = ActionMatcher.of<BehaviorTransition>(name = "transitionTo")), guard = GuardKey(name = key))
                }
            }
        val chart = StateChartDefinition(initial = states.getValue(key = initial::class), states = states.values.map { AtomicState(id = it) }, transitions = transitions)
        return Machine(id = id, version = version, chart = chart, config = implementation)
    }
}

@ActronStoreDsl
class BehaviorStateBuilder<C : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        private val source: StateId,
        private val implementation: MachineBuilder<C, A, CMD, E>,
        private val target: (C) -> StateId,
    ) {
        inline fun <reified T : A> on(noinline block: BehaviorActionScope<C, E>.(T) -> Unit) {
            register(matcher = ActionMatcher.of<T>(name = requireNotNull(value = T::class.simpleName)), block = { action ->
                require(value = action is T)
                block(action)
            })
        }

        /** Runs outside action processing; exit cancels it, resume restarts unfinished work. */
        fun onEnter(block: suspend BehaviorEntryScope<C, A, CMD, E>.() -> Unit) {
            require(value = source !in implementation.activities) { "Only one onEnter per state" }
            implementation.activities[source] = { execution ->
                BehaviorEntryScope<C, A, CMD, E>(
                    state = execution.context,
                    now = execution.now,
                    publish = execution.publish,
                    execute = execution.execute,
                ).block()
            }
            implementation.entryTransitions[source] = { proposed ->
                val state = proposed as C
                EntryDestination(context = state, target = this@BehaviorStateBuilder.target(state))
            }
        }

        @PublishedApi
        internal fun register(
            matcher: ActionMatcher,
            block: BehaviorActionScope<C, E>.(Action) -> Unit,
        ) {
            implementation.onAction(id = source, matcher = matcher, rule = {
                val native = this
                check(value = native is TransitionRequestScope)
                BehaviorActionScope<C, E>(
                    state = context,
                    now = now,
                    move = { next ->
                        native.requestTransition(target = this@BehaviorStateBuilder.target(next))
                        native.context = next
                    },
                    emit = { event -> native.event(event = event) },
                ).block(action)
            })
        }
    }

/** An action reads its original immutable snapshot; only transitionTo proposes a replacement. */
@ActronStoreDsl
class BehaviorActionScope<C : Any, E : Event> internal constructor(
    val state: C,
    val now: MachineTime,
    private val move: (C) -> Unit,
    private val emit: (E) -> Unit,
) {
    fun transitionTo(state: C) = move(state)

    fun event(event: E) = emit(event)
}

internal data class BehaviorTransition(
    val target: StateId,
) : Action

internal interface TransitionRequestScope {
    fun requestTransition(target: StateId)
}

internal sealed interface ActionDestination {
    data object Stay : ActionDestination

    data class Move(
        val target: StateId,
    ) : ActionDestination
}

/** Immutable entry data and state-owned suspend operations. Blocking I/O still needs its own dispatcher. */
@ActronStoreDsl
class BehaviorEntryScope<C : Any, A : Action, CMD : Any, E : Event> internal constructor(
    val state: C,
    val now: MachineTime,
    private val publish: (EntryOutput<A>) -> Unit,
    private val execute: suspend (CMD) -> Unit,
) {
    suspend fun transitionTo(state: C) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        publish(EntryOutput.Transition(context = state))
    }

    suspend fun command(command: CMD) = execute(command)

    suspend fun event(event: E) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        publish(EntryOutput.Emit(event = event))
    }
}

internal class EntryExecution<C : Any, A : Action, CMD : Any>(
    val context: C,
    val now: MachineTime,
    val publish: (EntryOutput<A>) -> Unit,
    val execute: suspend (CMD) -> Unit,
)

internal data class EntryDestination<C : Any>(
    val context: C,
    val target: StateId,
)
