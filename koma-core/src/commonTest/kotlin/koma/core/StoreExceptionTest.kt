package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreExceptionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    data class AppState(val value: Int) : State

    private fun createTestStore(
        initialState: AppState,
        exceptionHandler: ExceptionHandler,
    ): Store<AppState, Nothing, Nothing> {
        return Store(initialState) {
            this.coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(exceptionHandler)
            state<AppState> {
                enter {
                    throw RuntimeException("error")
                }
            }
        }
    }

    @Test
    fun store_shouldUseExceptionHandlerOnError() = runTest(testDispatcher) {
        var handledException: Throwable? = null

        val store = createTestStore(
            initialState = AppState(0),
            exceptionHandler = ExceptionHandler { error ->
                handledException = error
            },
        )

        store.collectState { } // start Store

        assertNotNull(handledException)
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecated_errorDslAlias_shouldStillRecover() = runTest(testDispatcher) {
        val store: Store<AppState, Nothing, Nothing> = Store(AppState(0)) {
            this.coroutineContext(Dispatchers.Unconfined)
            state<AppState> {
                enter {
                    throw RuntimeException("error")
                }
                error<RuntimeException> {
                    nextState { AppState(1) }
                }
            }
        }

        store.collectState { } // start Store

        assertEquals(AppState(1), store.currentState)
    }
}
