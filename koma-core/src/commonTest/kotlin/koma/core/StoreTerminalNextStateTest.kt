package koma.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** State transitions: Count(0) -> Count(1); failed/cancelled operations stay at Count(0). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("UNREACHABLE_CODE")
class StoreTerminalNextStateTest {
    data class Count(val value: Int = 0) : State
    data object Go : Action
    data object Updated : Event

    @Test
    fun actionExitsAtFirstNextState_andStoreContinuesProcessing() = runTest {
        val calls = mutableListOf<String>()
        val store: Store<Count, Go, Updated> = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go> {
                    try {
                        nextState { state.copy(value = state.value + 1) }
                    } finally {
                        calls += "finally"
                    }
                    event(Updated)
                    nextState { Count(100) }
                }
                recover<Exception> { calls += "recover" }
            }
        }
        store.collectEvent { calls += "event" }
        repeat(2) { store.dispatchAndAwaitForTest(Go) }
        assertEquals(Count(2), store.currentState)
        assertEquals(listOf("finally", "finally"), calls)
        store.close()
    }

    @Test
    fun enterAndRecoverExitAtNextState() = runTest {
        val calls = mutableListOf<String>()
        val store: Store<Count, Go, Nothing> = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                enter {
                    nextState { Count(1) }
                    calls += "after enter"
                }
                action<Go> { error("recover me") }
                recover<IllegalStateException> {
                    calls += "recover"
                    nextState { Count(2) }
                    calls += "after recover"
                }
            }
        }
        store.startAndAwaitForTest()
        assertEquals(Count(1), store.currentState)
        store.dispatchAndAwaitForTest(Go)
        assertEquals(Count(2), store.currentState)
        assertEquals(listOf("recover"), calls)
        store.close()
    }

    @Test
    fun bothTransactionsExitOnlyTheirOwnBlock() = runTest {
        val calls = mutableListOf<String>()
        val store: Store<Count, Go, Nothing> = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                enter {
                    launch {
                        transaction {
                            nextState { Count(state.value + 1) }
                            calls += "inside enter transaction"
                        }
                        calls += "after enter transaction"
                    }
                }
                action<Go> {
                    launch {
                        transaction {
                            nextState { Count(state.value + 1) }
                            calls += "inside action transaction"
                        }
                        calls += "after action transaction"
                    }
                }
            }
        }
        store.startAndAwaitForTest()
        advanceUntilIdle()
        store.dispatch(Go)
        advanceUntilIdle()
        assertEquals(Count(2), store.currentState)
        assertEquals(listOf("after enter transaction", "after action transaction"), calls)
        store.close()
    }

    @Test
    fun closedStoreRejectsNextStateBeforeCalculation() = runTest {
        var calculated = false
        lateinit var store: Store<Count, Go, Nothing>
        store = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go> {
                    store.close()
                    nextState { calculated = true; Count(1) }
                }
            }
        }
        store.dispatchAndAwaitForTest(Go)
        assertFalse(calculated)
        assertEquals(Count(), store.currentState)
    }

    @Test
    fun closingStoreDuringCalculationPreventsCommit() = runTest {
        lateinit var store: Store<Count, Go, Nothing>
        store = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go> {
                    nextState { store.close(); Count(1) }
                }
            }
        }
        store.dispatchAndAwaitForTest(Go)
        assertEquals(Count(), store.currentState)
    }

    @Test
    fun cancellingActionDoesNotCancelStore() = runTest {
        var cancel = true
        var calculated = false
        val store: Store<Count, Go, Nothing> = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go> {
                    if (cancel) currentCoroutineContext().cancel()
                    nextState { calculated = true; Count(1) }
                }
            }
        }
        store.dispatchAndAwaitForTest(Go)
        assertFalse(calculated)
        assertEquals(Count(), store.currentState)
        cancel = false
        store.dispatchAndAwaitForTest(Go)
        assertEquals(Count(1), store.currentState)
        store.close()
    }

    @Test
    fun cancelledHandlerWithDispatcherDoesNotCommit() = runTest {
        var calculated = false
        val store: Store<Count, Go, Nothing> = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go>(StandardTestDispatcher(testScheduler, "handler")) {
                    currentCoroutineContext().cancel()
                    nextState { calculated = true; Count(1) }
                }
            }
        }
        store.dispatchAndAwaitForTest(Go)
        assertFalse(calculated)
        assertEquals(Count(), store.currentState)
        store.close()
    }

    @Test
    fun closedStoreRejectsEventAndFollowingCode() = runTest {
        var continued = false
        lateinit var store: Store<Count, Go, Updated>
        store = Store(Count()) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Count> {
                action<Go> {
                    store.close()
                    event(Updated)
                    continued = true
                }
            }
        }
        store.dispatchAndAwaitForTest(Go)
        assertFalse(continued)
    }

    @Test
    fun transactionsRejectCancelledCallerUpdatesAndEvents() = runTest {
        for (emitEvent in listOf(false, true)) {
            var continued = false
            val store: Store<Count, Go, Updated> = Store(Count()) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                state<Count> {
                    action<Go> {
                        launch {
                            val callerJob = currentCoroutineContext()[Job]!!
                            transaction {
                                callerJob.cancel()
                                if (emitEvent) {
                                    event(Updated)
                                    continued = true
                                } else {
                                    nextState { continued = true; Count(1) }
                                }
                            }
                        }
                    }
                }
            }
            store.dispatch(Go)
            advanceUntilIdle()
            assertFalse(continued)
            assertEquals(Count(), store.currentState)
            store.close()
        }
    }
}
