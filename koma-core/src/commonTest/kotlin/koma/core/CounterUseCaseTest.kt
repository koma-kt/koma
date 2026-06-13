package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌─────────────────────────────────────────┐
// │                                         │
// │  Initial ─────────► Active ───────┐     │
// │                        ▲          │     │
// │                        │          │     │
// │                        │          │     │
// │                        │          ▼     │
// │  Error ◄── Any State   │        Paused  │
// │                        │          │     │
// │                        └──────────┘     │
// │                                         │
// └─────────────────────────────────────────┘
//
// Initial: Counter not started yet
// Active: Counter is running and can be incremented/decremented
// Paused: Counter is temporarily paused but retains its value
// Error: An error occurred during counter operation

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CounterUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // State definitions
    sealed interface AppState : State {
        data object Initial : AppState
        data class Active(val count: Int) : AppState
        data class Paused(val count: Int) : AppState
        data class Error(val message: String) : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data object Start : AppAction
        data object Increment : AppAction
        data object Decrement : AppAction
        data object Reset : AppAction
        data object Pause : AppAction
        data object Resume : AppAction
        data class ForceError(val message: String) : AppAction
    }

    // Event definitions
    sealed interface AppEvent : Event {
        data class CountChanged(val newCount: Int) : AppEvent
        data class ThresholdReached(val threshold: Int) : AppEvent
        data class ErrorOccurred(val message: String) : AppEvent
    }

    private fun createTestStore(
        initialState: AppState = AppState.Initial,
    ): Store<AppState, AppAction, AppEvent> {
        return Store(initialState) {
            // Configure coroutine context for tests
            coroutineContext(Dispatchers.Unconfined)

            // Initial state handling
            state<AppState.Initial> {
                action<AppAction.Start> {
                    nextState { AppState.Active(0) }
                }
            }

            // Active state handling
            state<AppState.Active> {
                enter {
                    // Optional initialization when entering active state
                    event(AppEvent.CountChanged(state.count))
                }

                action<AppAction.Increment> {
                    val newCount = state.count + 1
                    event(AppEvent.CountChanged(newCount))

                    // Emit threshold event if count reaches certain value
                    if (newCount == 10) {
                        event(AppEvent.ThresholdReached(10))
                    }

                    nextState { state.copy(count = newCount) }
                }

                action<AppAction.Decrement> {
                    val newCount = state.count - 1
                    event(AppEvent.CountChanged(newCount))
                    nextState { state.copy(count = newCount) }
                }

                action<AppAction.Reset> {
                    event(AppEvent.CountChanged(0))
                    nextState { state.copy(count = 0) }
                }

                action<AppAction.Pause> {
                    nextState { AppState.Paused(state.count) }
                }
            }

            // Paused state handling
            state<AppState.Paused> {
                action<AppAction.Resume> {
                    nextState { AppState.Active(state.count) }
                }
            }

            // Global error handling
            state<AppState> {
                action<AppAction.ForceError> {
                    throw RuntimeException(action.message)
                }
                recover<Exception> {
                    event(AppEvent.ErrorOccurred(error.message ?: "Unknown error"))
                    nextState { AppState.Error(error.message ?: "Unknown error") }
                }
            }
        }
    }

    @Test
    fun counter_initialState() = runTest(testDispatcher) {
        val store = createTestStore()
        assertEquals(AppState.Initial, store.currentState)
    }

    @Test
    fun counter_startShouldTransitionToActive() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.Start)

        assertTrue(store.currentState is AppState.Active)
        assertEquals(0, (store.currentState as AppState.Active).count)
    }

    @Test
    fun counter_incrementAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(0))

        store.dispatch(AppAction.Increment)

        assertEquals(AppState.Active(1), store.currentState)
    }

    @Test
    fun counter_decrementAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(5))

        store.dispatch(AppAction.Decrement)

        assertEquals(AppState.Active(4), store.currentState)
    }

    @Test
    fun counter_resetAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(10))

        store.dispatch(AppAction.Reset)

        assertEquals(AppState.Active(0), store.currentState)
    }

    @Test
    fun counter_pauseAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(3))

        store.dispatch(AppAction.Pause)

        assertTrue(store.currentState is AppState.Paused)
        assertEquals(3, (store.currentState as AppState.Paused).count)
    }

    @Test
    fun counter_resumeAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Paused(7))

        store.dispatch(AppAction.Resume)

        assertTrue(store.currentState is AppState.Active)
        assertEquals(7, (store.currentState as AppState.Active).count)
    }

    @Test
    fun counter_errorHandling() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(0))

        // Force an error by dispatching a special action
        store.dispatch(AppAction.ForceError("Test error"))

        assertTrue(store.currentState is AppState.Error)
        assertEquals("Test error", (store.currentState as AppState.Error).message)
    }

    @Test
    fun counter_eventEmission() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(5))

        var capturedEvent: AppEvent? = null
        store.collectEvent { event ->
            capturedEvent = event
        }

        store.dispatch(AppAction.Increment)

        assertNotNull(capturedEvent)
        assertTrue(capturedEvent is AppEvent.CountChanged)
        assertEquals(6, (capturedEvent as AppEvent.CountChanged).newCount)
    }

    @Test
    fun counter_thresholdReached() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Active(9))

        var thresholdEvent: AppEvent.ThresholdReached? = null
        store.collectEvent { event ->
            if (event is AppEvent.ThresholdReached) {
                thresholdEvent = event
            }
        }

        store.dispatch(AppAction.Increment) // This should reach 10

        assertNotNull(thresholdEvent)
        assertEquals(10, thresholdEvent?.threshold)
    }
}
