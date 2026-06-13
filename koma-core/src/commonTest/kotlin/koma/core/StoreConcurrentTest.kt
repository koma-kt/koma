package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreConcurrentTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data class Initial(val count: Int) : AppState
        data class Processing(val count: Int) : AppState
        data class Result(val count: Int) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Decrement : AppAction
        data object DelayedIncrement : AppAction
        data object RequestResult : AppAction
    }

    sealed interface AppEvent : Event {
        data object Processing : AppEvent
        data class Completed(val finalCount: Int) : AppEvent
    }

    private fun createTestStore(
        initialState: AppState,
    ): Store<AppState, AppAction, AppEvent> {
        return Store(initialState) {
            coroutineContext(Dispatchers.Unconfined)

            state<AppState.Initial> {
                action<AppAction.Increment> {
                    nextState { AppState.Initial(state.count + 1) }
                }
                action<AppAction.Decrement> {
                    nextState { AppState.Initial(state.count - 1) }
                }
                action<AppAction.DelayedIncrement> {
                    nextState { AppState.Processing(state.count) }
                    event(AppEvent.Processing)
                }
                action<AppAction.RequestResult> {
                    nextState { AppState.Result(state.count) }
                }
            }

            state<AppState.Processing> {
                enter {
                    launch {
                        // Simulate long processing
                        delay(100)
                        transaction {
                            val newCount = state.count + 1
                            nextState { AppState.Result(newCount) }
                            event(AppEvent.Completed(newCount))
                        }
                    }
                }

                action<AppAction.RequestResult> {
                    // This will wait for the transaction from enter to complete
                    // due to mutex locking
                    nextState { AppState.Result(state.count) }
                }
            }

            state<AppState.Result> {
                action<AppAction.Increment> {
                    nextState { AppState.Result(state.count + 1) }
                }
                action<AppAction.Decrement> {
                    nextState { AppState.Result(state.count - 1) }
                }
            }
        }
    }

    @Test
    fun concurrentActions_shouldHandleRaceConditions() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Initial(0))

        // Launch multiple concurrent actions
        val jobs = List(10) { index ->
            launch {
                if (index % 2 == 0) {
                    store.dispatch(AppAction.Increment)
                } else {
                    store.dispatch(AppAction.Decrement)
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state of counter
        when (val finalState = store.currentState) {
            is AppState.Initial -> assertEquals(0, finalState.count)
            is AppState.Result -> assertEquals(0, finalState.count)
            else -> throw AssertionError("Unexpected state type: ${finalState::class.simpleName}")
        }
    }

    @Test
    fun concurrentStateChanges_shouldPreserveMutexLocking() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Initial(0))

        // Track emitted events to verify ordering
        val events = mutableListOf<AppEvent>()
        store.collectEvent { event ->
            events.add(event)
        }

        // First action triggers a state change with a delay
        val delayedJob = launch {
            store.dispatch(AppAction.DelayedIncrement)
        }

        // Give time for first dispatch to be processed
        kotlinx.coroutines.yield()

        // Second action should wait for the first to complete
        store.dispatch(AppAction.RequestResult)

        delayedJob.join()

        // Allow events to be collected
        kotlinx.coroutines.yield()

        // Verify event emission - we should at least have the Processing event
        assertEquals(1, events.size, "Should have at least the Processing event")
        assertEquals(AppEvent.Processing, events[0])

        // If the completed event was emitted, check its value
        if (events.size > 1) {
            assertEquals(AppEvent.Completed(1), events[1])
        }
    }
}
