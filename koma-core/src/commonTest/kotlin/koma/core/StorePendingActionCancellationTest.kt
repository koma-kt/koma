package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StorePendingActionCancellationTest {

    sealed interface AppState : State {
        data class Active(val value: Int = 0) : AppState
    }

    sealed interface AppAction : Action {
        data object HoldAndCancel : AppAction
        data object LaunchTransactionAndCancel : AppAction
        data object Increment : AppAction
    }

    private fun createTestStore(
        testDispatcher: TestDispatcher,
        onHoldAndCancelStarted: CompletableDeferred<Unit>? = null,
        onHoldAndCancelCompleted: CompletableDeferred<Unit>? = null,
        onTransactionStarted: CompletableDeferred<Unit>? = null,
        onTransactionCompleted: CompletableDeferred<Unit>? = null,
    ): Store<AppState, AppAction, Nothing> {
        return Store(AppState.Active()) {
            coroutineContext(testDispatcher)
            pendingActionPolicy(PendingActionPolicy.Keep)

            state<AppState.Active> {
                action<AppAction.HoldAndCancel>(testDispatcher) {
                    onHoldAndCancelStarted?.complete(Unit)
                    delay(100)
                    clearPendingActions()
                    onHoldAndCancelCompleted?.complete(Unit)
                    nextState { state.copy(value = state.value + 100) }
                }

                action<AppAction.LaunchTransactionAndCancel>(testDispatcher) {
                    launch(testDispatcher) {
                        transaction(testDispatcher) {
                            onTransactionStarted?.complete(Unit)
                            delay(100)
                            clearPendingActions()
                            onTransactionCompleted?.complete(Unit)
                            nextState { state.copy(value = state.value + 100) }
                        }
                    }
                }

                action<AppAction.Increment>(testDispatcher) {
                    nextState { state.copy(value = state.value + 1) }
                }
            }
        }
    }

    @Test
    fun cancelPendingActions_inAction_skipsQueuedDispatches() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val actionStarted = CompletableDeferred<Unit>()
        val actionCompleted = CompletableDeferred<Unit>()
        val store = createTestStore(
            testDispatcher = testDispatcher,
            onHoldAndCancelStarted = actionStarted,
            onHoldAndCancelCompleted = actionCompleted,
        )

        store.dispatch(AppAction.HoldAndCancel)
        runCurrent()
        actionStarted.await()

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)

        advanceUntilIdle()

        assertEquals(true, actionCompleted.isCompleted, "The running action should not cancel itself")
        assertEquals(AppState.Active(value = 100), store.currentState)
    }

    @Test
    fun cancelPendingActions_inTransaction_skipsQueuedDispatches() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transactionStarted = CompletableDeferred<Unit>()
        val transactionCompleted = CompletableDeferred<Unit>()
        val store = createTestStore(
            testDispatcher = testDispatcher,
            onTransactionStarted = transactionStarted,
            onTransactionCompleted = transactionCompleted,
        )

        store.dispatch(AppAction.LaunchTransactionAndCancel)
        runCurrent()
        transactionStarted.await()

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)

        advanceUntilIdle()

        assertEquals(true, transactionCompleted.isCompleted, "The running transaction should complete")
        assertEquals(AppState.Active(value = 100), store.currentState)
    }
}
