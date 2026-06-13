package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreSaverTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    data class AppState(val value: Int) : State

    sealed interface AppAction : Action {
        data class Update(val value: Int) : AppAction
    }

    private fun createTestStore(
        initialState: AppState,
        stateSaver: StateSaver<AppState>,
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
        errorStateOnException: AppState? = null,
    ): Store<AppState, AppAction, Nothing> {
        return Store(initialState) {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(stateSaver)
            exceptionHandler(exceptionHandler)
            state<AppState> {
                action<AppAction.Update> {
                    nextState { state.copy(value = action.value) }
                }
                if (errorStateOnException != null) {
                    recover<Exception> {
                        nextState { errorStateOnException }
                    }
                }
            }
        }
    }

    @Test
    fun store_shouldUseStateSaverToRestoreState() = runTest(testDispatcher) {
        var savedState = AppState(10)

        val stateSaver = StateSaver(
            save = { state -> savedState = state },
            restore = { savedState },
        )

        val store = createTestStore(AppState(0), stateSaver)

        assertEquals(AppState(10), store.currentState)

        store.dispatch(AppAction.Update(20))

        assertEquals(AppState(20), savedState)
    }

    @Test
    fun store_saveException_isHandledWithoutUsingStoreErrorHandling() = runTest(testDispatcher) {
        var handledException: Throwable? = null
        val store = createTestStore(
            initialState = AppState(0),
            stateSaver = StateSaver(
                save = { throw IllegalArgumentException("save failed") },
                restore = { null },
            ),
            exceptionHandler = ExceptionHandler { handledException = it },
            errorStateOnException = AppState(-1),
        )

        store.dispatch(AppAction.Update(20))
        testScheduler.runCurrent()

        assertEquals(AppState(20), store.currentState)
        val error = assertIs<IllegalArgumentException>(handledException)
        assertEquals("save failed", error.message)
    }
}
