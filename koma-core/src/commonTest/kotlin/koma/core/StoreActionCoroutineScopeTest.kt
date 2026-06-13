package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreActionCoroutineScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private class DirectThrowable(message: String) : Throwable(message)

    sealed interface AppState : State {
        data class Active(val value: Int = 0) : AppState
        data class Completed(val value: Int = 0) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object LaunchIncrement : AppAction
        data class LaunchWithDelta(val delta: Int) : AppAction
        data object LaunchLongRunning : AppAction
        data object MoveToCompleted : AppAction
        data object MoveToCompletedThenLaunch : AppAction
        data object LaunchThrow : AppAction
        data object LaunchTransactionThrow : AppAction
        data object LaunchFatal : AppAction
        data object LaunchTransactionFatal : AppAction
        data object LaunchBareThrowable : AppAction
    }

    private fun createTestStore(
        onLongRunningStart: (() -> Unit)? = null,
        onLongRunningCancelled: (() -> Unit)? = null,
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
    ): Store<AppState, AppAction, Nothing> {
        return Store(AppState.Active()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(exceptionHandler)

            state<AppState.Active> {
                action<AppAction.LaunchIncrement> {
                    launch {
                        transaction {
                            nextState { state.copy(value = state.value + 1) }
                        }
                    }
                }

                action<AppAction.LaunchWithDelta> {
                    launch {
                        val delta = action.delta
                        transaction {
                            nextState { state.copy(value = state.value + delta + action.delta) }
                        }
                    }
                }

                action<AppAction.LaunchLongRunning> {
                    launch {
                        onLongRunningStart?.invoke()
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onLongRunningCancelled?.invoke()
                        }
                    }
                }

                action<AppAction.MoveToCompleted> {
                    nextState { AppState.Completed(value = state.value) }
                }

                action<AppAction.MoveToCompletedThenLaunch> {
                    nextState { AppState.Completed(value = state.value + 1) }
                    launch {
                        transaction {
                            nextState { AppState.Completed(value = 999) }
                        }
                    }
                }

                action<AppAction.LaunchThrow> {
                    launch {
                        throw IllegalStateException("launch failed")
                    }
                }

                action<AppAction.LaunchFatal> {
                    launch {
                        throw AssertionError("fatal launch")
                    }
                }

                action<AppAction.LaunchBareThrowable> {
                    launch {
                        throw DirectThrowable("bare throwable")
                    }
                }

                action<AppAction.LaunchTransactionThrow> {
                    launch {
                        transaction {
                            throw IllegalArgumentException("transaction failed")
                        }
                    }
                }

                action<AppAction.LaunchTransactionFatal> {
                    launch {
                        transaction {
                            throw AssertionError("fatal transaction")
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
    }

    @Test
    fun actionLaunch_updatesStateViaTransaction() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.LaunchIncrement)
        yield()

        assertEquals(AppState.Active(value = 1), store.currentState)
    }

    @Test
    fun actionLaunch_canReferenceActionInLaunchAndTransaction() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.LaunchWithDelta(delta = 2))
        yield()

        assertEquals(AppState.Active(value = 4), store.currentState)
    }

    @Test
    fun actionLaunch_isCancelledWhenStateChanges() = runTest(testDispatcher) {
        var started = false
        var cancelled = false
        val store = createTestStore(
            onLongRunningStart = { started = true },
            onLongRunningCancelled = { cancelled = true },
        )

        store.dispatch(AppAction.LaunchLongRunning)
        yield()
        assertTrue(started, "Long-running action launch should start")

        store.dispatch(AppAction.MoveToCompleted)
        yield()

        assertEquals(AppState.Completed(value = 0), store.currentState)
        assertTrue(cancelled, "Long-running action launch should be cancelled on state transition")
    }

    @Test
    fun actionLaunch_afterNextState_bindsToOriginalState() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.MoveToCompletedThenLaunch)
        repeat(3) { yield() }

        assertEquals(AppState.Completed(value = 1), store.currentState)
    }

    @Test
    fun actionLaunch_exception_isHandledByErrorBlock() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.LaunchThrow)
        repeat(3) { yield() }

        assertEquals(AppState.Failed("launch failed"), store.currentState)
    }

    @Test
    fun actionLaunch_transactionException_isHandledByErrorBlock() = runTest(testDispatcher) {
        val store = createTestStore()

        store.dispatch(AppAction.LaunchTransactionThrow)
        repeat(3) { yield() }

        assertEquals(AppState.Failed("transaction failed"), store.currentState)
    }

    @Test
    fun actionLaunch_fatalError_isForwardedToExceptionHandler() = runTest(testDispatcher) {
        var handledThrowable: Throwable? = null
        val store = createTestStore(
            exceptionHandler = ExceptionHandler { handledThrowable = it },
        )

        store.dispatch(AppAction.LaunchFatal)
        repeat(3) { yield() }

        assertNotNull(handledThrowable)
        val error = handledThrowable
        assertIs<AssertionError>(error)
        assertEquals("fatal launch", error.message)
        assertEquals(AppState.Active(value = 0), store.currentState)
    }

    @Test
    fun actionLaunch_nonExceptionThrowable_isForwardedToExceptionHandler() = runTest(testDispatcher) {
        var handledThrowable: Throwable? = null
        val store = createTestStore(
            exceptionHandler = ExceptionHandler { handledThrowable = it },
        )

        store.dispatch(AppAction.LaunchBareThrowable)
        repeat(3) { yield() }

        assertNotNull(handledThrowable)
        val error = handledThrowable
        assertIs<DirectThrowable>(error)
        assertEquals("bare throwable", error.message)
        assertEquals(AppState.Active(value = 0), store.currentState)
    }

    @Test
    fun actionLaunch_transactionFatalError_isForwardedToExceptionHandler() = runTest(testDispatcher) {
        var handledThrowable: Throwable? = null
        val store = createTestStore(
            exceptionHandler = ExceptionHandler { handledThrowable = it },
        )

        store.dispatch(AppAction.LaunchTransactionFatal)
        repeat(3) { yield() }

        assertNotNull(handledThrowable)
        val error = handledThrowable
        assertIs<AssertionError>(error)
        assertEquals("fatal transaction", error.message)
        assertEquals(AppState.Active(value = 0), store.currentState)
    }
}
