package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌───────────────────────────────────────────────┐
// │                                               │
// │  Initial ────────► Active ────────► Completed │
// │                                               │
// └───────────────────────────────────────────────┘
//
// Initial: Flow collection not started
// Active: Flow is being collected with current data value
// Completed: Flow collection completed

@OptIn(ExperimentalCoroutinesApi::class)
class FlowCollectUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // State definitions
    sealed interface AppState : State {
        data object Initial : AppState
        data class Active(val value: Int) : AppState
        data object Completed : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data object StartCollecting : AppAction
        data object Complete : AppAction
    }

    private fun createTestStore(
        dataFlow: Flow<Int>,
        initialState: AppState = AppState.Initial,
    ): Store<AppState, AppAction, Nothing> {
        return Store(initialState) {
            // Configure coroutine context for tests
            coroutineContext(Dispatchers.Unconfined)

            // Initial state handling
            state<AppState.Initial> {
                action<AppAction.StartCollecting> {
                    nextState { AppState.Active(0) } // Start with a default value before flow collection
                }
            }

            // Active state handling
            state<AppState.Active> {
                enter {
                    // Use launch to collect the flow
                    launch {
                        dataFlow.collect { value ->
                            transaction {
                                nextState { state.copy(value = value) }
                            }
                        }
                    }
                }

                action<AppAction.Complete> {
                    nextState { AppState.Completed }
                }
            }

            // Completed state handling
            state<AppState.Completed> {
                // No additional behavior needed
            }
        }
    }

    @Test
    fun flowCollect_collectsItemsAndDispatchesActions() = runTest(testDispatcher) {
        // Create a simple flow that emits integers immediately
        val testFlow = flowOf(1, 2, 3, 4, 5)

        // Create store with the test flow
        val store = createTestStore(testFlow)

        // Start state check
        assertEquals(AppState.Initial, store.currentState)

        // Start collecting from flow
        store.dispatch(AppAction.StartCollecting)

        // With UnconfinedTestDispatcher, the flow should be collected immediately
        assertTrue(
            store.currentState is AppState.Active,
            "State should be Active, but was ${store.currentState}",
        )

        // Should have the last value
        assertEquals(5, (store.currentState as AppState.Active).value)

        // Complete the collection
        store.dispatch(AppAction.Complete)
        assertEquals(AppState.Completed, store.currentState)
    }

    @Test
    fun flowCollect_cancellingStateStopsCollection() = runTest(testDispatcher) {
        // Create a flow that we can observe collection status
        var collectionCancelled = false
        val testFlow = flow {
            try {
                emit(42)
                // Just hang here until cancelled
                delay(Long.MAX_VALUE)
            } finally {
                collectionCancelled = true
            }
        }

        // Create store with the flow
        val store = createTestStore(testFlow)

        // Start collecting
        store.dispatch(AppAction.StartCollecting)

        // Should have received first value
        assertTrue(store.currentState is AppState.Active)
        assertEquals(42, (store.currentState as AppState.Active).value)

        // Stop collecting by transitioning away from the state
        store.dispatch(AppAction.Complete)

        // Collection should have stopped
        assertEquals(AppState.Completed, store.currentState)

        // Verify collection was cancelled
        assertTrue(collectionCancelled, "Flow collection should have been cancelled")

        // This flow will be cancelled automatically when the test completes
    }
}
