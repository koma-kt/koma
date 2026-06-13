package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌─────────────────────────────┐
// │     ┌───────────────┐       │
// │     │               │       │
// │     ▼               │       │
// │    Idle ────────► Active    │
// │     ▲              │  ▲     │
// │     │              │  │     │
// │     │              ▼  │     │
// │     └───────────── Error    │
// │                             │
// │           Active            │
// │             │               │
// │             ▼               │
// │   ┌─────────────────────┐   │
// │   │                     │   │
// │   │  Processing Events  │   │
// │   │                     │   │
// │   └─────────────────────┘   │
// │                             │
// └─────────────────────────────┘
//
// Idle: Initial state or unsubscribed state, no active subscription
// Active: Actively subscribed to events and processing them
// Error: Error occurred during event processing

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventStreamUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Domain models
    data class DataEvent(
        val data: String,
        val timestamp: Long,
    )

    // Event provider interfaces
    interface EventProvider {
        fun registerCallback(
            onEvent: (DataEvent) -> Unit,
            onError: (Throwable) -> Unit,
        ): RegistrationHandle

        interface RegistrationHandle {
            fun unregister()
        }
    }

    // Mock implementation for testing
    class MockEventProvider : EventProvider {
        private var onEvent: ((DataEvent) -> Unit)? = null
        private var onError: ((Throwable) -> Unit)? = null
        private var isRegistered = false

        private val registrationHandle = object : EventProvider.RegistrationHandle {
            override fun unregister() {
                isRegistered = false
                onEvent = null
                onError = null
            }
        }

        override fun registerCallback(
            onEvent: (DataEvent) -> Unit,
            onError: (Throwable) -> Unit,
        ): EventProvider.RegistrationHandle {
            this.onEvent = onEvent
            this.onError = onError
            isRegistered = true
            return registrationHandle
        }

        fun emitEvent(event: DataEvent) {
            if (isRegistered) {
                onEvent?.invoke(event)
            }
        }

        fun emitError(error: Throwable) {
            if (isRegistered) {
                onError?.invoke(error)
            }
        }
    }

    // Monitor class that wraps callback-based API with Flow
    class EventStreamMonitor(private val eventProvider: EventProvider) {
        val eventStream: Flow<Result<DataEvent>> = callbackFlow {
            val handle = eventProvider.registerCallback(
                onEvent = { event ->
                    trySend(Result.success(event))
                },
                onError = { error ->
                    trySend(Result.failure(error))
                },
            )

            awaitClose {
                handle.unregister()
            }
        }
    }

    // Koma State definitions
    sealed interface AppState : State {
        data object Idle : AppState
        data class Active(val lastProcessedData: String? = null) : AppState
        data class Error(val error: Throwable) : AppState
    }

    // Koma Action definitions
    sealed interface AppAction : Action {
        data object Subscribe : AppAction
        data object Unsubscribe : AppAction
    }

    private fun createTestStore(
        eventStreamMonitor: EventStreamMonitor,
    ): Store<AppState, AppAction, Nothing> {
        return Store {
            initialState(AppState.Idle)
            coroutineContext(Dispatchers.Unconfined)

            state<AppState.Idle> {
                action<AppAction.Subscribe> {
                    // Direct transition to Active state
                    nextState { AppState.Active() }
                }
            }

            state<AppState.Active> {
                enter {
                    // Collect events from the stream
                    launch {
                        eventStreamMonitor.eventStream.collect { result ->
                            result.fold(
                                onSuccess = { event ->
                                    transaction {
                                        // Update state with new data while staying in Active state
                                        nextState { AppState.Active(lastProcessedData = event.data) }
                                    }
                                },
                                onFailure = { error ->
                                    transaction {
                                        nextState { AppState.Error(error) }
                                    }
                                },
                            )
                        }
                    }
                }

                action<AppAction.Unsubscribe> {
                    nextState { AppState.Idle }
                }
            }

            state<AppState.Error> {
                action<AppAction.Subscribe> {
                    nextState { AppState.Active() }
                }

                action<AppAction.Unsubscribe> {
                    nextState { AppState.Idle }
                }
            }
        }
    }

    @Test
    fun eventStream_shouldSubscribeAndProcessEvents() = runTest(testDispatcher) {
        // Mock implementation of event provider
        val mockEventProvider = MockEventProvider()
        val eventStreamMonitor = EventStreamMonitor(mockEventProvider)

        // Create store with idle state
        val store = createTestStore(eventStreamMonitor)

        // Initial state should be Idle
        assertIs<AppState.Idle>(store.currentState)

        // Start subscription - goes directly to Active state
        store.dispatch(AppAction.Subscribe)

        // State should change directly to Active
        assertIs<AppState.Active>(store.currentState)

        // Simulate receiving first event
        val firstEvent = DataEvent("first-data", 1234567890L) // Fixed timestamp for test
        mockEventProvider.emitEvent(firstEvent)

        // State should update with the processed data
        val stateAfterFirstEvent = store.currentState
        assertIs<AppState.Active>(stateAfterFirstEvent)
        assertEquals(firstEvent.data, stateAfterFirstEvent.lastProcessedData)

        // Simulate receiving second event - should continue to process in Active state
        val secondEvent = DataEvent("second-data", 1234567891L) // Fixed timestamp for test
        mockEventProvider.emitEvent(secondEvent)

        // State should update again with the new data
        val stateAfterSecondEvent = store.currentState
        assertIs<AppState.Active>(stateAfterSecondEvent)
        assertEquals(secondEvent.data, stateAfterSecondEvent.lastProcessedData)

        // Unsubscribe
        store.dispatch(AppAction.Unsubscribe)

        // State should return to Idle
        assertIs<AppState.Idle>(store.currentState)
    }

    @Test
    fun eventStream_shouldHandleProcessingErrors() = runTest(testDispatcher) {
        // Mock implementation of event provider
        val mockEventProvider = MockEventProvider()
        val eventStreamMonitor = EventStreamMonitor(mockEventProvider)

        // Create store with idle state
        val store = createTestStore(eventStreamMonitor)

        // Start subscription - goes directly to Active state
        store.dispatch(AppAction.Subscribe)

        // State should change directly to Active
        assertIs<AppState.Active>(store.currentState)

        // Simulate error event
        val error = Exception("Processing error")
        mockEventProvider.emitError(error)

        // State should change to Error
        val currentState = store.currentState
        assertIs<AppState.Error>(currentState)
        assertEquals(error, currentState.error)
    }
}
