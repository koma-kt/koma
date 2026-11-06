package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cancelled operations must leave unrelated work intact: Count(0) -> Count(1). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreOperationCancellationTest {
    data class Count(val value: Int = 0) : State
    data object Go : Action
    data object Increment : Action

    @Test
    fun cancelledHandlersCannotLaunchWork() = runTest {
        for (fromEnter in listOf(false, true)) {
            var launched = false
            var continued = false
            val store: Store<Count, Go, Nothing> = Store(Count()) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                state<Count> {
                    if (fromEnter) {
                        enter(StandardTestDispatcher(testScheduler, "enter")) {
                            currentCoroutineContext().cancel()
                            launch { launched = true }
                            continued = true
                        }
                    } else {
                        action<Go>(StandardTestDispatcher(testScheduler, "action")) {
                            currentCoroutineContext().cancel()
                            launch { launched = true }
                            continued = true
                        }
                    }
                }
            }
            store.dispatch(Go)
            advanceUntilIdle()
            assertFalse(launched)
            assertFalse(continued)
            store.close()
        }
    }

    @Test
    fun cancelledActionCannotClearQueuedActions() = runTest {
        var continued = false
        lateinit var store: Store<Count, Action, Nothing>
        store = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go>(StandardTestDispatcher(testScheduler, "action")) {
                    store.dispatch(Increment)
                    currentCoroutineContext().cancel()
                    clearPendingActions()
                    continued = true
                }
                action<Increment> { nextState { Count(state.value + 1) } }
            }
        }
        store.dispatch(Go)
        advanceUntilIdle()
        assertFalse(continued)
        assertEquals(Count(1), store.currentState)
        store.close()
    }

    @Test
    fun cancelledTransactionCallersCannotClearQueuedActions() = runTest {
        for (fromEnter in listOf(false, true)) {
            var continued = false
            lateinit var store: Store<Count, Action, Nothing>
            store = Store(Count()) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                state<Count> {
                    if (fromEnter) {
                        enter {
                            launch {
                                val callerJob = currentCoroutineContext()[Job]!!
                                transaction {
                                    store.dispatch(Increment)
                                    callerJob.cancel()
                                    clearPendingActions()
                                    continued = true
                                }
                            }
                        }
                    } else {
                        action<Go> {
                            launch {
                                val callerJob = currentCoroutineContext()[Job]!!
                                transaction {
                                    store.dispatch(Increment)
                                    callerJob.cancel()
                                    clearPendingActions()
                                    continued = true
                                }
                            }
                        }
                    }
                    action<Increment> { nextState { Count(state.value + 1) } }
                }
            }
            store.dispatch(Go)
            advanceUntilIdle()
            assertFalse(continued)
            assertEquals(Count(1), store.currentState)
            store.close()
        }
    }

    @Test
    fun cancelledActionCannotCancelOrReplaceTrackedLaunch() = runTest {
        for (replace in listOf(false, true)) {
            val lane = LaunchLane()
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            var replacementStarted = false
            var continued = false
            val store: Store<Count, Go, Nothing> = Store(Count()) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                state<Count> {
                    action<Go>(StandardTestDispatcher(testScheduler, "action")) {
                        launch(control = LaunchControl.CancelPrevious(lane)) {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }
                        started.await()
                        currentCoroutineContext().cancel()
                        if (replace) {
                            launch(control = LaunchControl.CancelPrevious(lane)) {
                                replacementStarted = true
                            }
                        } else {
                            cancelLaunch(lane)
                        }
                        continued = true
                    }
                }
            }
            store.dispatch(Go)
            advanceUntilIdle()
            assertTrue(started.isCompleted)
            assertFalse(cancelled)
            assertFalse(replacementStarted)
            assertFalse(continued)
            store.close()
            advanceUntilIdle()
            assertTrue(cancelled)
        }
    }
}
