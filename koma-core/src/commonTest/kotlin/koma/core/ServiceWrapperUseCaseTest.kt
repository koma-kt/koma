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
// ┌──────────────────────────┐
// │                          │
// │    Idle ────► Running    │
// │     ▲            │       │
// │     │            │       │
// │     └────────────┘       │
// │                          │
// └──────────────────────────┘
//
// Idle: Initial state, service not started
// Running: Service is active, receiving callbacks

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceWrapperUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    interface MyService {
        fun register(listener: Listener)
        fun unRegister(listener: Listener)
        interface Listener {
            fun onCountUpdated(count: Int)
        }
    }

    // Mock implementation for testing
    class MockMyService : MyService {
        private val listeners = mutableListOf<MyService.Listener>()

        override fun register(listener: MyService.Listener) {
            listeners.add(listener)
        }

        override fun unRegister(listener: MyService.Listener) {
            listeners.remove(listener)
        }

        fun triggerCallbackForTest(count: Int) {
            listeners.forEach { it.onCountUpdated(count) }
        }
    }

    class MyServiceMonitor(private val myService: MyService) {
        val count: Flow<Int> = callbackFlow {
            val listener = object : MyService.Listener {
                override fun onCountUpdated(count: Int) {
                    trySend(count)
                }
            }

            myService.register(listener)

            awaitClose {
                myService.unRegister(listener)
            }
        }
    }

    sealed interface AppState : State {
        data object Idle : AppState
        data class Running(val count: Int) : AppState
    }

    sealed interface AppAction : Action {
        data object Start : AppAction
        data object Stop : AppAction
    }

    private fun createTestStore(
        myServiceMonitor: MyServiceMonitor,
    ): Store<AppState, AppAction, Nothing> {
        return Store {
            initialState(AppState.Idle)
            coroutineContext(Dispatchers.Unconfined)

            state<AppState.Idle> {
                action<AppAction.Start> {
                    nextState { AppState.Running(count = 0) }
                }
            }

            state<AppState.Running> {
                enter {
                    launch {
                        myServiceMonitor.count.collect {
                            transaction {
                                nextState { state.copy(count = it) }
                            }
                        }
                    }
                }

                action<AppAction.Stop> {
                    nextState { AppState.Idle }
                }
            }
        }
    }

    @Test
    fun serviceWrapper_shouldStartAndHandleCallbacks() = runTest(testDispatcher) {
        // Mock implementation of MyService
        val mockService = MockMyService()
        val serviceWrapper = MyServiceMonitor(mockService)

        // Create store with idle state
        val store = createTestStore(serviceWrapper)

        // Initial state should be Idle
        assertIs<AppState.Idle>(store.currentState)

        // Start the service
        store.dispatch(AppAction.Start)

        // State should change to Running with count 0
        assertIs<AppState.Running>(store.currentState)
        assertEquals(0, (store.currentState as AppState.Running).count)

        // Trigger callback from service with count update
        mockService.triggerCallbackForTest(5)

        // State should update with new count
        assertEquals(5, (store.currentState as AppState.Running).count)

        // Stop the service
        store.dispatch(AppAction.Stop)

        // State should return to Idle
        assertIs<AppState.Idle>(store.currentState)
    }
}
