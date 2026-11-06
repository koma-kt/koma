package consumer

import actron.compose.rememberViewStore
import actron.core.Action
import actron.core.Event
import actron.decompose.retainedMachine
import actron.logging.Logger
import actron.logging.simpleLogging
import actron.message.Message
import actron.message.message
import actron.message.receiveMessages
import actron.observability.StoreInstanceId
import actron.statechart.compose.MailboxEffect
import actron.statechart.machine.*
import actron.timetravel.MachinePersistence
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

fun <C : Any, A : Action, CMD : Any, E : Event> restorePublishedCheckpoint(
    persistence: MachinePersistence<C, A, CMD, E>,
    checkpoint: ExecutorCheckpoint<C, CMD>,
): ExecutorCheckpoint<C, CMD> = persistence.restore(persistence.save(checkpoint))

data class Counter(
    val count: Int = 0,
)

data object Increment : Action

data object Ev : Event

data object Ping : Message

data object SendPing

val machine =
    typedStateMachine<Counter, Increment, SendPing, Ev>(
        DefinitionId("published-counter"),
        DefinitionVersion("1"),
        Counter(),
    ) {
        state<Counter> {
            on<Increment> { target { state.copy(count = state.count + 1) } }
            onEnter { if (state.count > 0) command(SendPing) }
        }
    }

fun counter(scope: CoroutineScope): MachineStore<Counter, Increment, SendPing, Ev> =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> message(Ping) }, scope, coroutineContext = scope.coroutineContext) {
        plugin(simpleLogging(logger = Logger { _, _, _ -> }))
    }

fun receiver(scope: CoroutineScope): MachineStore<Counter, Increment, SendPing, Ev> =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> }, scope, coroutineContext = scope.coroutineContext) {
        plugin(receiveMessages { if (it == Ping) dispatch(Increment) })
    }

fun runningMachine(scope: CoroutineScope): MachineStore<Counter, Increment, SendPing, Ev> {
    val group = MachineGroup()
    val member = group.member<Counter, Increment, SendPing, Ev>(StoreInstanceId("published"))
    return MachineStore(
        machine,
        Counter(),
        CommandHandler { _, _ -> },
        scope,
        coroutineContext = scope.coroutineContext,
        observers = listOf(member),
    ).also { member.attach(it) }
}

@Composable
fun CounterUi(store: MachineStore<Counter, Increment, SendPing, Ev>): Int = rememberViewStore(store).select { it.context.count }

@Composable
fun MachineUi(store: MachineStore<Counter, Increment, SendPing, Ev>) {
    MailboxEffect(store.mailbox) { it.acknowledge() }
}

fun <C : Any, A : Action, CMD : Any, E : Event> ComponentContext.installPublishedMachine(
    key: String,
    machine: Machine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
) = retainedMachine(key, machine, initial, handler)
