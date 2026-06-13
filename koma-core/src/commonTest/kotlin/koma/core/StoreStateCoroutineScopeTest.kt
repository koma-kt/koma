package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌───────────────────────────────────────────┐
// │                                           │
// │  Initial ─────────► Running ────► Final   │
// │                       │                   │
// │                       │                   │
// │                       ▼                   │
// │                  (Background              │
// │                   Operations)             │
// │                                           │
// └───────────────────────────────────────────┘
//
// Initial: Starting state, no background work
// Running: Active state with a background coroutine
// Final: End state, background work is cancelled

@OptIn(ExperimentalCoroutinesApi::class)
class StoreStateCoroutineScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // State definitions
    sealed interface AppState : State {
        data object Initial : AppState
        data class Running(val value: Int = 0) : AppState
        data class Failed(val message: String) : AppState
        data object Final : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data object Start : AppAction
        data object Stop : AppAction
    }

    // Event definitions
    sealed interface AppEvent : Event {
        data class ValueChanged(val value: Int) : AppEvent
        data object Completed : AppEvent
    }

    // Store factory for basic functionality test
    private fun createTestStore(
        withCancellation: Boolean = false,
        onBackgroundTaskStart: (() -> Unit)? = null,
        onBackgroundTaskCancel: (() -> Unit)? = null,
    ): Store<AppState, AppAction, AppEvent> {
        return Store(AppState.Initial) {
            // Configure to use Unconfined dispatcher
            coroutineContext(Dispatchers.Unconfined)

            // Initial state handling
            state<AppState.Initial> {
                action<AppAction.Start> {
                    nextState { AppState.Running() }
                }
            }

            // Running state with background operations
            state<AppState.Running> {
                enter {
                    // Launch background task with launch function
                    launch {
                        if (withCancellation) {
                            try {
                                onBackgroundTaskStart?.invoke()

                                // Long-running task that should be cancelled
                                delay(10000)

                                // We shouldn't reach this
                                throw IllegalStateException("Background task wasn't cancelled")
                            } catch (e: Exception) {
                                onBackgroundTaskCancel?.invoke()
                            }
                        } else {
                            transaction {
                                nextState { state.copy(value = 5) }
                            }

                            // Emit event
                            event(AppEvent.ValueChanged(5))
                        }
                    }
                }

                // Stop action transitions to Final
                action<AppAction.Stop> {
                    nextState { AppState.Final }
                }
            }

            // Final state
            state<AppState.Final> {
                enter {
                    if (!withCancellation) {
                        event(AppEvent.Completed)
                    }
                }
            }
        }
    }

    @Test
    fun enterCoroutineScope_dispatchesActionsAndEmitsEvents() = runTest(testDispatcher) {
        // Create store with test dispatcher
        val store = createTestStore()

        // Initial state check
        assertEquals(AppState.Initial, store.currentState)

        // Transition to Running state
        store.dispatch(AppAction.Start)

        // Verify state transition occurred
        assertTrue(store.currentState is AppState.Running)

        // Verify background task updated state via action dispatch
        val runningState = store.currentState as AppState.Running
        assertEquals(5, runningState.value)

        // Move to final state
        store.dispatch(AppAction.Stop)

        // Verify final state
        assertEquals(AppState.Final, store.currentState)
    }

    @Test
    fun enterCoroutineScope_cancelsBackgroundTaskOnStateTransition() = runTest(testDispatcher) {
        // Flags to track background task execution
        var backgroundTaskRan = false
        var backgroundTaskCancelled = false

        // Create store with custom handling
        val store = createTestStore(
            withCancellation = true,
            onBackgroundTaskStart = { backgroundTaskRan = true },
            onBackgroundTaskCancel = { backgroundTaskCancelled = true },
        )

        // Start sequence
        assertEquals(AppState.Initial, store.currentState)
        store.dispatch(AppAction.Start)
        assertEquals(AppState.Running::class, store.currentState::class)

        // Verify background task started
        assertTrue(backgroundTaskRan, "Background task should have started")

        // Immediately transition to final state
        store.dispatch(AppAction.Stop)
        assertEquals(AppState.Final, store.currentState)

        // Verify background task was cancelled
        assertTrue(backgroundTaskCancelled, "Background task should have been cancelled")
    }

    @Test
    fun enterLaunch_transactionFatalError_isForwardedToExceptionHandler() = runTest(testDispatcher) {
        var handledThrowable: Throwable? = null
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Initial) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handledThrowable = it })

            state<AppState.Initial> {
                action<AppAction.Start> {
                    nextState { AppState.Running() }
                }
            }

            state<AppState.Running> {
                enter {
                    launch {
                        transaction {
                            throw AssertionError("fatal enter transaction")
                        }
                    }
                }
            }

            state<AppState> {
                recover<Exception> {
                    nextState { AppState.Failed(error.message ?: "unknown") }
                }
            }
        }

        store.dispatch(AppAction.Start)
        repeat(3) { kotlinx.coroutines.yield() }

        assertNotNull(handledThrowable)
        val error = handledThrowable
        assertIs<AssertionError>(error)
        assertEquals("fatal enter transaction", error.message)
        assertEquals(AppState.Running(), store.currentState)
    }

}
