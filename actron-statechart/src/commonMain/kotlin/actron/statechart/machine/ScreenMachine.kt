package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.StateChartDefinition
import kotlin.time.Duration

/** A declared graph whose external work runs directly in its activation-owned entry blocks. */
class ScreenMachine<S : Any, A : Action, E : Event> internal constructor(
    id: DefinitionId,
    version: DefinitionVersion,
    chart: StateChartDefinition,
    implementation: MachineBuilder<S, A, Unit, E>,
) : Machine<S, A, Unit, E>(id, version, chart, implementation)

fun <S : Any, A : Action, E : Event> screenMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    build: ScreenMachineBuilder<S, A, E>.() -> Unit,
): ScreenMachine<S, A, E> {
    val implementation = MachineBuilder<S, A, Unit, E>()
    val root = TypedStateMachineBuilder<S, S, A, Unit, E>({ it }, { _, state -> state }, implementation)
    ScreenMachineBuilder(root).build()
    return ScreenMachine(id, version, root.definition(initial), implementation)
}

@ActronStoreDsl
class ScreenMachineBuilder<S : Any, A : Action, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val root: TypedStateMachineBuilder<S, S, A, Unit, E>,
    ) {
        inline fun <reified T : S> state(noinline build: ScreenStateBuilder<S, A, E>.() -> Unit = {}) {
            root.state<T> { ScreenStateBuilder(this).build() }
        }
    }

@ActronStoreDsl
class ScreenStateBuilder<S : Any, A : Action, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val rules: TypedMachineStateBuilder<S, S, A, Unit, E>,
    ) {
        inline fun <reified T : A> on(noinline transition: TransitionRuleBuilder<S, S, T>.() -> Unit) = rules.on(transition)

        fun always(transition: TransitionRuleBuilder<S, S, Unit>.() -> Unit) = rules.always(transition)

        fun after(
            delay: Duration,
            transition: TransitionRuleBuilder<S, S, Unit>.() -> Unit,
        ) = rules.after(delay, transition)

        /** Runs after commit, cancels on exit, and restarts unfinished entry work on restore. */
        fun onEnter(block: suspend ScreenEntryScope<S, A, E>.() -> Unit) {
            rules.delegate.entryActivity(block)
        }
    }

@ActronStoreDsl
interface ScreenEntryScope<out S : Any, in A : Action, in E : Event> {
    val state: S

    suspend fun dispatch(action: A)

    suspend fun event(event: E)
}

/** For executor adapters that also accept command-based machines. */
fun <A : Action> noCommandHandler(): CommandHandler<Unit, A> = CommandHandler { _, _ -> error("A screen machine cannot register commands") }
