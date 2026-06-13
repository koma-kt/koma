package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreBaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data object Loading : AppState
        data class Main(val count: Int) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Decrement : AppAction
        data object EmitEvent : AppAction
    }

    sealed interface AppEvent : Event {
        data class CountUpdated(val count: Int) : AppEvent
    }

    private fun createTestStore(
        initialState: AppState,
        autoStartPolicy: AutoStartPolicy = AutoStartPolicy.OnDispatchOrStateCollection,
    ): Store<AppState, AppAction, AppEvent> {
        return Store(initialState) {
            coroutineContext(Dispatchers.Unconfined)
            autoStartPolicy(autoStartPolicy)
            state<AppState.Loading> {
                enter {
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
                action<AppAction.Decrement> {
                    nextState { state.copy(count = state.count - 1) }
                }
                action<AppAction.EmitEvent> {
                    event(AppEvent.CountUpdated(state.count))
                }
            }
        }
    }

    @Test
    fun tartStore_shouldProcessInitialEnterWhenCollectingState() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Loading)

        // Store is not started
        assertIs<AppState.Loading>(store.currentState)

        // start Store
        store.collectState { }

        // Store is started
        assertIs<AppState.Main>(store.currentState)
    }

    @Test
    fun tartStore_shouldProcessInitialEnterWhenCollectingStateFlow() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Loading)

        // Store is not started
        assertIs<AppState.Loading>(store.currentState)

        val collectingJob = launch {
            // start Store
            store.state.collect { }
        }

        assertIs<AppState.Main>(store.currentState)

        collectingJob.cancel()
    }

    @Test
    fun tartStore_withDispatchOnlyAutoStart_shouldNotProcessInitialEnterWhenCollectingState() = runTest(testDispatcher) {
        val store = createTestStore(
            initialState = AppState.Loading,
            autoStartPolicy = AutoStartPolicy.OnDispatch,
        )
        var observedState: AppState? = null

        store.collectState { state ->
            observedState = state
        }

        assertEquals(AppState.Loading, observedState)
        assertIs<AppState.Loading>(store.currentState)
    }

    @Test
    fun tartStore_dispatchBeforeStart_shouldInitializeAndHandleAction() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Loading)

        store.dispatch(AppAction.Increment)

        assertEquals(AppState.Main(1), store.currentState)
    }

    @Test
    fun tartStore_start_shouldInitializeWithoutDispatch() = runTest(testDispatcher) {
        val store = createTestStore(
            initialState = AppState.Loading,
            autoStartPolicy = AutoStartPolicy.OnDispatch,
        )

        store.start()

        assertEquals(AppState.Main(count = 0), store.currentState)
    }

    @Test
    fun tartStore_shouldHandleActions() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Loading)

        // Store is not started
        assertIs<AppState.Loading>(store.currentState)

        // start Store and handle action
        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Decrement)

        assertEquals(AppState.Main(1), store.currentState)
    }

    @Test
    fun tartStore_dispatchAndAwait_shouldSuspendUntilActionHandled() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Loading> {
                enter {
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    gate.await()
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        val dispatchJob = launch {
            store.dispatchAndAwaitForTest(AppAction.Increment)
        }

        assertFalse(dispatchJob.isCompleted)
        assertEquals(AppState.Main(0), store.currentState)

        gate.complete(Unit)
        dispatchJob.join()

        assertEquals(AppState.Main(1), store.currentState)
    }

    @Test
    fun tartStore_dispatchQueuedBeforeDispatchTriggeredStartupCompletes_shouldSurviveStateExit() = runTest(testDispatcher) {
        val startupEntered = CompletableDeferred<Unit>()
        val startupGate = CompletableDeferred<Unit>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Loading> {
                enter {
                    startupEntered.complete(Unit)
                    startupGate.await()
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        val firstDispatchJob = launch {
            store.dispatchAndAwaitForTest(AppAction.Increment)
        }
        startupEntered.await()

        val secondDispatchJob = launch {
            store.dispatchAndAwaitForTest(AppAction.Increment)
        }

        assertFalse(firstDispatchJob.isCompleted)
        assertFalse(secondDispatchJob.isCompleted)
        assertIs<AppState.Loading>(store.currentState)

        startupGate.complete(Unit)
        firstDispatchJob.join()
        secondDispatchJob.join()

        assertEquals(AppState.Main(2), store.currentState)
    }

    @Test
    fun tartStore_dispatchQueuedDuringStateCollectionStartup_shouldSurviveStateExit() = runTest(testDispatcher) {
        val startupEntered = CompletableDeferred<Unit>()
        val startupGate = CompletableDeferred<Unit>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Loading> {
                enter {
                    startupEntered.complete(Unit)
                    startupGate.await()
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        val collectingJob = launch {
            store.state.collect { }
        }
        startupEntered.await()

        val dispatchJob = launch {
            store.dispatchAndAwaitForTest(AppAction.Increment)
        }

        assertFalse(dispatchJob.isCompleted)
        assertIs<AppState.Loading>(store.currentState)

        startupGate.complete(Unit)
        dispatchJob.join()
        collectingJob.cancel()

        assertEquals(AppState.Main(1), store.currentState)
    }

    @Test
    fun tartStore_dispatchQueuedDuringExplicitStartup_shouldSurviveStateExit() = runTest(testDispatcher) {
        val startupEntered = CompletableDeferred<Unit>()
        val startupGate = CompletableDeferred<Unit>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            autoStartPolicy(AutoStartPolicy.OnDispatch)
            state<AppState.Loading> {
                enter {
                    startupEntered.complete(Unit)
                    startupGate.await()
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        store.start()
        startupEntered.await()

        val dispatchJob = launch {
            store.dispatchAndAwaitForTest(AppAction.Increment)
        }

        assertFalse(dispatchJob.isCompleted)
        assertIs<AppState.Loading>(store.currentState)

        startupGate.complete(Unit)
        dispatchJob.join()

        assertEquals(AppState.Main(1), store.currentState)
    }

    @Test
    fun tartStore_shouldEmitEvents() = runTest(testDispatcher) {
        val store = createTestStore(AppState.Loading)

        var emittedEvent: AppEvent? = null
        store.collectEvent { event ->
            emittedEvent = event
        }

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.EmitEvent)

        assertNotNull(emittedEvent)
        assertEquals(AppEvent.CountUpdated(1), emittedEvent)
    }
}
