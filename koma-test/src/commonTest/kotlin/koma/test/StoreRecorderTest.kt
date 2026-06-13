package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.StateSaver
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecorderTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data object Loading : AppState
        data class Main(val count: Int) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object EmitEvent : AppAction
    }

    sealed interface AppEvent : Event {
        data class CountUpdated(val count: Int) : AppEvent
    }

    private class RecordingStateSaver(
        private val restoredState: AppState?,
    ) : StateSaver<AppState> {
        override fun save(state: AppState) = Unit

        override fun restore(): AppState? = restoredState
    }

    private fun createTestStore(): Store<AppState, AppAction, AppEvent> {
        return Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Loading> {
                enter {
                    nextState(AppState.Main(count = 0))
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState(state.copy(count = state.count + 1))
                }
                action<AppAction.EmitEvent> {
                    event(AppEvent.CountUpdated(state.count))
                }
            }
        }
    }

    @Test
    fun createRecorder_recordsStatesAndEvents() = runTest(testDispatcher) {
        val store = createTestStore()
        val recorder = store.createRecorder()
        store.dispatchAndAwait(AppAction.Increment)
        store.dispatchAndAwait(AppAction.EmitEvent)

        assertEquals(
            listOf(
                AppState.Loading,
                AppState.Main(count = 0),
                AppState.Main(count = 1),
            ),
            recorder.states,
        )
        assertEquals(
            listOf(
                AppEvent.CountUpdated(count = 1),
            ),
            recorder.events,
        )
    }

    @Test
    fun recorder_clear_resetsRecordedHistory() = runTest(testDispatcher) {
        val store = createTestStore()
        val recorder = store.createRecorder()
        store.dispatchAndAwait(AppAction.Increment)
        store.dispatchAndAwait(AppAction.EmitEvent)

        assertTrue(recorder.states.isNotEmpty())
        assertTrue(recorder.events.isNotEmpty())
        recorder.clear()

        assertTrue(recorder.states.isEmpty())
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun record_runsBlockWithRecorderAndStoreReceiver() = runTest(testDispatcher) {
        val store = createTestStore()

        store.record { recorder ->
            dispatchAndAwait(AppAction.Increment)
            dispatchAndAwait(AppAction.EmitEvent)

            assertEquals(
                listOf(
                    AppState.Loading,
                    AppState.Main(count = 0),
                    AppState.Main(count = 1),
                ),
                recorder.states,
            )
            assertEquals(
                listOf(
                    AppEvent.CountUpdated(count = 1),
                ),
                recorder.events,
            )
        }
    }

    @Test
    fun startAndAwait_waitsUntilStartupCompletes() = runTest(testDispatcher) {
        val store = createTestStore()

        store.startAndAwait()

        assertEquals(AppState.Main(count = 0), store.currentState)
    }

    @Test
    fun patch_shouldReconfigureStoreBeforeFirstUse() {
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(RecordingStateSaver(restoredState = null))
        }.patch {
            stateSaver(RecordingStateSaver(restoredState = AppState.Main(count = 7)))
        }

        assertEquals(AppState.Main(count = 7), store.currentState)
    }

    @Test
    fun extensions_throwForStoresThatDoNotImplementTestingInterfaces() = runTest(testDispatcher) {
        val store = FakeStore()

        assertFailsWith<IllegalStateException> {
            store.createRecorder()
        }
        try {
            store.startAndAwait()
            fail("Expected startAndAwait to fail for stores without StoreInternalApi support")
        } catch (_: IllegalStateException) {
        }
        try {
            store.dispatchAndAwait(AppAction.Increment)
            fail("Expected dispatchAndAwait to fail for stores without StoreInternalApi support")
        } catch (_: IllegalStateException) {
        }
        try {
            store.patch { }
            fail("Expected patch to fail for stores without StoreInternalApi support")
        } catch (_: IllegalStateException) {
        }
    }

    private class FakeStore :
        Store<AppState, AppAction, AppEvent> {
        override val state: StateFlow<AppState> = MutableStateFlow(AppState.Loading)
        override val event: Flow<AppEvent> = emptyFlow()
        override val currentState: AppState = AppState.Loading

        override fun start() = Unit

        override fun dispatch(action: AppAction) = Unit

        override fun collectState(state: (AppState) -> Unit) = Unit

        override fun collectEvent(event: (AppEvent) -> Unit) = Unit

        override fun close() = Unit
    }
}
