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

class TypedNestedParallelPersistenceTest {
    @Serializable private sealed interface Phase {
        @Serializable data object Paused : Phase

        @Serializable data object Active : Phase

        @Serializable data class Running(
            val left: Step = Step.Loading,
            val right: Step = Step.Loading,
        ) : Phase

        @Serializable data object Done : Phase

        @Serializable data object Finished : Phase
    }

    @Serializable private sealed interface Step {
        @Serializable data object Loading : Step

        @Serializable data object Done : Step
    }

    private data object Left

    private data object Right

    @Serializable private sealed interface Act : Action {
        @Serializable data object Resume : Act

        @Serializable data object Pause : Act

        @Serializable data object LeftDone : Act

        @Serializable data object RightDone : Act
    }

    @Serializable private sealed interface Ev : Event

    private fun machine(deep: Boolean) =
        typedStateMachine<Phase, Act, String, Ev>(
            DefinitionId("nested-parallel"),
            DefinitionVersion("1"),
            Phase.Paused,
        ) {
            state(Phase.Paused) { onHistory<Act.Resume, Phase.Active>() }
            compound<Phase.Active>(Phase.Running()) {
                history(deep)
                on<Act.Pause, Phase.Paused>()
                onDone<Phase.Finished>()
                parallel(Phase.Running()) {
                    onDone<Phase.Done>()
                    region<Left, Step>(Step.Loading, { (it as Phase.Running).left }, { c, step -> (c as Phase.Running).copy(left = step) }) {
                        state(Step.Loading) {
                            onEnter { command("left") }
                            on<Act.LeftDone, Step.Done>()
                        }
                        final(Step.Done)
                    }
                    region<Right, Step>(Step.Loading, { (it as Phase.Running).right }, { c, step -> (c as Phase.Running).copy(right = step) }) {
                        state(Step.Loading) {
                            onEnter { command("right") }
                            on<Act.RightDone, Step.Done>()
                        }
                        final(Step.Done)
                    }
                }
                final(Phase.Done)
            }
            final(Phase.Finished)
        }

    @Test
    fun restoresParallelRegionsHistoryAndCascadingCompletion() =
        runTest {
            for (deep in listOf(false, true)) {
                val definition = machine(deep)
                val persistence = machinePersistence(definition)
                val calls = mutableListOf<String>()
                val handler =
                    CommandHandler<String, Act> { command, _ ->
                        calls += command.command
                        awaitCancellation()
                    }
                var store = persistence.resume(persistence.save(ExecutorCheckpoint.initial(definition.initialSnapshot(Phase.Paused))), handler, backgroundScope)
                store.start()
                testScheduler.runCurrent()

                suspend fun restore() {
                    val before = store.currentState
                    val saved = persistence.save(store)
                    store.close()
                    store = machinePersistence(machine(deep)).resume(saved, handler, backgroundScope)
                    store.start()
                    testScheduler.runCurrent()
                    assertEquals(before, store.currentState)
                    assertTrue(definition.checkInvariants(store.currentState).isEmpty())
                }

                fun send(action: Act) {
                    store.dispatch(action)
                    testScheduler.runCurrent()
                }
                send(Act.Resume)
                send(Act.LeftDone)
                restore()
                assertEquals(listOf("left", "right", "right"), calls)
                assertEquals(Step.Done, (store.currentState.context as Phase.Running).left)
                assertEquals(Step.Loading, (store.currentState.context as Phase.Running).right)
                send(Act.Pause)
                restore()
                send(Act.Resume)
                assertEquals(if (deep) Step.Done else Step.Loading, (store.currentState.context as Phase.Running).left)
                restore()
                if (!deep) send(Act.LeftDone)
                send(Act.RightDone)
                assertEquals(Phase.Finished, store.currentState.context)
                assertTrue(definition.isComplete(store.currentState))
                val count = calls.size
                restore()
                assertTrue(definition.isComplete(store.currentState))
                assertTrue(store.currentState.commands.isEmpty())
                assertEquals(count, calls.size, "Completed regions must not execute again")
                send(Act.Resume)
                assertEquals(Phase.Finished, store.currentState.context)
                store.close()
            }
        }
}
