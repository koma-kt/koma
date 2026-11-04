@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ParallelPersistenceTest {
    @Serializable private sealed interface Phase {
        @Serializable data object Idle : Phase

        @Serializable data object Loading : Phase

        @Serializable data class Ready(
            val value: Int,
        ) : Phase

        @Serializable data object Timeout : Phase
    }

    @Serializable private data class Context(
        val left: Phase = Phase.Idle,
        val right: Phase = Phase.Idle,
    )

    @Serializable private sealed interface Input : Action {
        @Serializable data object Load : Input

        @Serializable data object RetryLeft : Input

        @Serializable data class LeftDone(
            val value: Int,
        ) : Input

        @Serializable data class RightDone(
            val value: Int,
        ) : Input
    }

    @Serializable private sealed interface Output : Event

    private fun machine() =
        parallelStateMachine<Context, Input, String, Output>(DefinitionId("parallel"), DefinitionVersion("1")) {
            region("Left", Phase.Idle, { it.left }, { context, state: Phase -> context.copy(left = state) }) {
                state<Phase.Idle> { on<Input.Load, Phase.Loading> { _, _ -> Phase.Loading } }
                state<Phase.Loading> {
                    onEnter { command("left") }
                    on<Input.LeftDone, Phase.Ready> { _, action -> Phase.Ready(action.value) }
                    on<Input.RetryLeft, Phase.Loading> { _, _ -> Phase.Loading }
                    after(delay = 2.seconds) { target { Phase.Timeout } }
                }
                state<Phase.Ready>()
                state<Phase.Timeout>()
            }
            region("Right", Phase.Idle, { it.right }, { context, state: Phase -> context.copy(right = state) }) {
                state<Phase.Idle> { on<Input.Load, Phase.Loading> { _, _ -> Phase.Loading } }
                state<Phase.Loading> {
                    onEnter { command("right") }
                    on<Input.RightDone, Phase.Ready> { _, action -> Phase.Ready(action.value) }
                    after(delay = 5.seconds) { target { Phase.Timeout } }
                }
                state<Phase.Ready>()
                state<Phase.Timeout>()
            }
        }

    @Test
    fun restoresPartialResultsAndOnlyPendingRegionThenFiresRemainingTimer() =
        runTest {
            val persistence = machinePersistence(machine())
            val calls = mutableListOf<String>()
            val handler =
                CommandHandler<String, Input> { command, _ ->
                    calls += command.command
                    awaitCancellation()
                }
            val clock = MachineClock.monotonic(testScheduler.timeSource)
            val first = persistence.resume(persistence.save(ExecutorCheckpoint.initial(machine().initialSnapshot(Context()))), handler, backgroundScope, clock = clock)
            first.start()
            testScheduler.runCurrent()
            first.dispatch(Input.Load)
            testScheduler.runCurrent()
            first.dispatch(Input.RightDone(42))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500)
            val saved = persistence.save(first)
            first.close()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(60000)
            val second = machinePersistence(machine()).resume(saved, handler, backgroundScope, clock = clock)
            second.start()
            testScheduler.runCurrent()
            assertEquals(first.currentState, second.currentState)
            assertEquals(listOf("left", "right", "left"), calls)
            testScheduler.advanceTimeBy(1499)
            testScheduler.runCurrent()
            assertEquals(Context(Phase.Loading, Phase.Ready(42)), second.currentState.context)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals(Context(Phase.Timeout, Phase.Ready(42)), second.currentState.context)
            val again = persistence.save(second)
            second.close()
            val third = persistence.resume(again, handler, backgroundScope, clock = clock)
            third.start()
            testScheduler.runCurrent()
            assertEquals(second.currentState, third.currentState)
            assertEquals(3, calls.size)
            third.close()
        }
}
