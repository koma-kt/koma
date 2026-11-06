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

class TypedHierarchyPersistenceTest {
    @Serializable private sealed interface Phase {
        val count: Int get() = 0

        @Serializable data class Paused(
            override val count: Int = 0,
        ) : Phase

        @Serializable data object Active : Phase

        @Serializable data object Nested : Phase

        @Serializable data class First(
            override val count: Int = 0,
        ) : Phase

        @Serializable data class Second(
            override val count: Int = 0,
        ) : Phase

        @Serializable data object Done : Phase

        @Serializable data class Result(
            override val count: Int,
        ) : Phase

        @Serializable data object Finished : Phase
    }

    @Serializable private sealed interface Act : Action {
        @Serializable data object Resume : Act

        @Serializable data object Restart : Act

        @Serializable data object Next : Act

        @Serializable data object Pause : Act

        @Serializable data object Increment : Act
    }

    @Serializable private sealed interface Ev : Event

    private fun hierarchy(deep: Boolean) =
        typedStateMachine<Phase, Act, String, Ev>(
            DefinitionId("hierarchy"),
            DefinitionVersion("1"),
            Phase.Paused(),
        ) {
            state<Phase.Paused>(enter = { Phase.Paused(it.count) }) {
                onHistory<Act.Resume, Phase.Active>()
                on<Act.Restart, Phase.Active>()
            }
            compound<Phase.Active>(initial = Phase.Nested) {
                history(deep = deep)
                transitions {
                    on<Act.Pause, Phase.Paused>()
                    // Child handler has priority over this transition.
                    on<Act.Next, Phase.Paused>()
                }
                compound<Phase.Nested>(initial = Phase.First()) {
                    state<Phase.First>(enter = { Phase.First(it.count) }) {
                        onEnter {
                            check(state is Phase.First)
                            command("load")
                        }
                        on<Act.Next, Phase.Second>()
                    }
                    state<Phase.Second>(enter = { Phase.Second(it.count) }) {
                        internal<Act.Increment> { context, _ -> Phase.Second(context.count + 1) }
                    }
                }
            }
        }

    @Test
    fun restoresNestedStateAndShallowOrDeepHistoryThenContinues() =
        runTest {
            for (deep in listOf(false, true)) {
                val definition = hierarchy(deep)
                val persistence = machinePersistence(definition)
                var calls = 0
                val handler =
                    CommandHandler<String, Act> { _, _ ->
                        calls++
                        awaitCancellation()
                    }
                var store = persistence.resume(persistence.save(ExecutorCheckpoint.initial(definition.initialSnapshot(Phase.Paused()))), handler, backgroundScope)
                store.start()
                testScheduler.runCurrent()

                suspend fun restore() {
                    val before = store.currentState
                    val saved = persistence.save(store)
                    store.close()
                    store = machinePersistence(hierarchy(deep)).resume(saved, handler, backgroundScope)
                    store.start()
                    testScheduler.runCurrent()
                    assertEquals(before, store.currentState, "Restore must not execute entry hooks or change activations")
                    assertTrue(definition.checkInvariants(store.currentState).isEmpty())
                }

                suspend fun send(action: Act) {
                    store.dispatch(action)
                    testScheduler.runCurrent()
                    restore()
                }
                send(Act.Resume)
                assertEquals(Phase.First(), store.currentState.context)
                assertEquals(2, calls, "Only the pending load restarts")
                send(Act.Next)
                send(Act.Increment)
                assertEquals(Phase.Second(1), store.currentState.context)
                send(Act.Pause)
                assertTrue(
                    store.currentState.configuration.history
                        .isNotEmpty(),
                )
                send(Act.Resume)
                assertEquals(if (deep) Phase.Second(1) else Phase.First(1), store.currentState.context)
                assertEquals(1, store.currentState.context.count)
                send(Act.Pause)
                send(Act.Restart)
                assertEquals(Phase.First(1), store.currentState.context)
                store.close()
            }
        }
}
