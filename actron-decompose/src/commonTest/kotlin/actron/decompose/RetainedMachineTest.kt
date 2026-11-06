package actron.decompose

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.typedStateMachine
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RetainedMachineTest {
    private data class Counter(
        val count: Int = 0,
    )

    private data object Increment : Action

    private sealed interface Output : Event

    private fun machine(startCommand: Boolean = true) =
        typedStateMachine<Counter, Increment, String, Output>(
            DefinitionId("counter"),
            DefinitionVersion("1"),
            Counter(),
        ) {
            state<Counter> {
                onEnter { if (startCommand) command("wait") }
                internal<Increment> { value, _ -> value.copy(count = value.count + 1) }
            }
        }

    @Test
    fun retainedInstallationStartsOnceAndKeeperClosesCommands() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            var started = 0
            var cancelled = 0
            val handler =
                CommandHandler<String, Increment> { _, _ ->
                    started++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                    }
                }

            fun install() =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    "counter",
                    machine(),
                    Counter(),
                    handler,
                    coroutineContext = coroutineContext,
                    initialActions = listOf(Increment),
                )
            val store = install()
            testScheduler.runCurrent()
            assertEquals(1, store.state.value.context.count)
            assertSame(store, install())
            testScheduler.runCurrent()
            assertEquals(1, started)
            assertEquals(1, store.state.value.context.count)
            keeper.destroy()
            testScheduler.runCurrent()
            assertEquals(1, cancelled)
        }

    @Test
    fun checkpointRestoresWithoutRecordingAndDoesNotRepeatInitialActions() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val definition = machine(startCommand = false)
            val started =
                definition
                    .decide(
                        definition.initialSnapshot(Counter(9)),
                        actron.statechart.machine.MachineInput
                            .Start(actron.statechart.machine.MachineTime.Zero),
                    ).snapshot
            val checkpoint = ExecutorCheckpoint<Counter, String>(started, actron.statechart.machine.MachineTime.Zero)
            val store =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    "restored",
                    definition,
                    Counter(),
                    CommandHandler { _, _ -> awaitCancellation() },
                    restoration = MachineRestoration { _, _ -> checkpoint },
                    coroutineContext = coroutineContext,
                    initialActions = listOf(Increment),
                )
            testScheduler.runCurrent()
            assertEquals(9, store.state.value.context.count)
            store.dispatch(Increment)
            testScheduler.runCurrent()
            assertEquals(10, store.state.value.context.count)
            keeper.destroy()
        }

    @Test
    fun rejectsForeignFreshCheckpointBeforeStartingAnything() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val definition = machine(startCommand = false)
            val foreign = ExecutorCheckpoint.initial<Counter, String>(definition.initialSnapshot(Counter()).copy(definition = DefinitionId("foreign")))
            try {
                assertFailsWith<IllegalArgumentException> {
                    DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                        "foreign",
                        definition,
                        Counter(),
                        CommandHandler { _, _ -> awaitCancellation() },
                        restoration = MachineRestoration { _, _ -> foreign },
                        coroutineContext = coroutineContext,
                    )
                }
            } finally {
                keeper.destroy()
            }
        }
}
