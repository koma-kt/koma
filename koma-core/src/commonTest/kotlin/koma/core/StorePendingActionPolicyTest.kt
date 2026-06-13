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
class StorePendingActionPolicyTest {

    sealed interface AppState : State {
        data object Initial : AppState
        data class Active(val value: Int = 0) : AppState
    }

    sealed interface AppAction : Action {
        data object EnterActiveAfterDelay : AppAction
        data object UpdateInPlaceAfterDelay : AppAction
        data object Increment : AppAction
    }

    private fun createStore(
        testDispatcher: TestDispatcher,
        pendingActionPolicy: PendingActionPolicy = PendingActionPolicy.ClearOnStateExit,
        onTransitionStarted: CompletableDeferred<Unit>? = null,
        onTransitionCompleted: CompletableDeferred<Unit>? = null,
    ): Store<AppState, AppAction, Nothing> {
        return Store(AppState.Initial) {
            coroutineContext(testDispatcher)
            pendingActionPolicy(pendingActionPolicy)

            state<AppState.Initial> {
                action<AppAction.EnterActiveAfterDelay>(testDispatcher) {
                    onTransitionStarted?.complete(Unit)
                    delay(100)
                    onTransitionCompleted?.complete(Unit)
                    nextState { AppState.Active() }
                }
            }

            state<AppState.Active> {
                action<AppAction.UpdateInPlaceAfterDelay>(testDispatcher) {
                    delay(100)
                    nextState { state.copy(value = state.value + 100) }
                }

                action<AppAction.Increment>(testDispatcher) {
                    nextState { state.copy(value = state.value + 1) }
                }
            }
        }
    }

    @Test
    fun clearOnStateExit_dropsQueuedActionsAfterStateTransition() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transitionStarted = CompletableDeferred<Unit>()
        val transitionCompleted = CompletableDeferred<Unit>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onTransitionStarted = transitionStarted,
            onTransitionCompleted = transitionCompleted,
        )

        store.dispatch(AppAction.EnterActiveAfterDelay)
        runCurrent()
        transitionStarted.await()

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)

        advanceUntilIdle()

        assertEquals(true, transitionCompleted.isCompleted, "The running transition should complete")
        assertEquals(AppState.Active(value = 0), store.currentState)
    }

    @Test
    fun keep_preservesQueuedActionsAfterStateTransition() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transitionStarted = CompletableDeferred<Unit>()
        val store = createStore(
            testDispatcher = testDispatcher,
            pendingActionPolicy = PendingActionPolicy.Keep,
            onTransitionStarted = transitionStarted,
        )

        store.dispatch(AppAction.EnterActiveAfterDelay)
        runCurrent()
        transitionStarted.await()

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)

        advanceUntilIdle()

        assertEquals(AppState.Active(value = 2), store.currentState)
    }

    @Test
    fun clearOnStateExit_keepsQueuedActionsForSameStateUpdates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val inPlaceUpdateStarted = CompletableDeferred<Unit>()
        val store = Store<AppState, AppAction, Nothing>(AppState.Active()) {
            coroutineContext(testDispatcher)

            state<AppState.Active> {
                action<AppAction.UpdateInPlaceAfterDelay>(testDispatcher) {
                    inPlaceUpdateStarted.complete(Unit)
                    delay(100)
                    nextState { state.copy(value = state.value + 100) }
                }

                action<AppAction.Increment>(testDispatcher) {
                    nextState { state.copy(value = state.value + 1) }
                }
            }
        }

        store.dispatch(AppAction.UpdateInPlaceAfterDelay)
        runCurrent()
        inPlaceUpdateStarted.await()

        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)

        advanceUntilIdle()

        assertEquals(AppState.Active(value = 102), store.currentState)
    }
}
