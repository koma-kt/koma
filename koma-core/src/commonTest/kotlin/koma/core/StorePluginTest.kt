package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StorePluginTest {

    private class CountingStateSaver<S : State>(
        private val restoredState: S? = null,
    ) : StateSaver<S> {
        var restoreCalls: Int = 0

        override fun save(state: S) = Unit

        override fun restore(): S? {
            restoreCalls += 1
            return restoredState
        }
    }

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int = 0) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Emit : AppAction
        data object Throw : AppAction
        data object TriggerPluginDispatch : AppAction
    }

    sealed interface AppEvent : Event {
        data class CountUpdated(val count: Int) : AppEvent
    }

    @Test
    fun pluginHooks_shouldObserveStoreLifecycle() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val records = mutableListOf<String>()

        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(testDispatcher)
            plugin(
                Plugin(
                    onStart = { state ->
                        records += "start:$state"
                    },
                    onAction = { state, action ->
                        records += "action:$state:$action"
                    },
                    onState = { prevState, state ->
                        records += "state:$prevState->$state"
                    },
                    onEvent = { state, event ->
                        records += "event:$state:$event"
                    },
                ),
            )

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }

            state<AppState.Ready> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }

                action<AppAction.Emit> {
                    event(AppEvent.CountUpdated(count = state.count))
                }

                action<AppAction.Throw> {
                    throw IllegalStateException("action failed")
                }
            }

            state<AppState> {
                recover<Exception> {
                    nextState { AppState.Failed(error.message ?: "unknown") }
                }
            }
        }

        store.dispatch(AppAction.Increment)
        runCurrent()

        store.dispatch(AppAction.Emit)
        runCurrent()

        store.dispatch(AppAction.Throw)
        runCurrent()

        store.close()

        assertEquals(
            listOf(
                "start:Loading",
                "state:Loading->Ready(count=0)",
                "action:Ready(count=0):Increment",
                "state:Ready(count=0)->Ready(count=1)",
                "action:Ready(count=1):Emit",
                "event:Ready(count=1):CountUpdated(count=1)",
                "action:Ready(count=1):Throw",
                "state:Ready(count=1)->Failed(message=action failed)",
            ),
            records,
        )
    }

    @Test
    fun pluginScope_shouldAllowDispatchAndStoreScopedLaunch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val launchGate = CompletableDeferred<Unit>()
        val records = mutableListOf<String>()

        val store = Store<AppState, AppAction, Nothing>(AppState.Loading) {
            coroutineContext(testDispatcher)
            plugin(
                object : Plugin<AppState, AppAction, Nothing> {
                    override suspend fun onStart(scope: PluginScope<AppState, AppAction>, state: AppState) {
                        scope.launch {
                            launchGate.await()
                            records += "launch:$currentState"
                        }
                    }

                    override suspend fun onAction(scope: PluginScope<AppState, AppAction>, state: AppState, action: AppAction) {
                        if (action == AppAction.TriggerPluginDispatch) {
                            scope.launch {
                                dispatch(AppAction.Increment)
                            }
                        }
                    }
                },
            )

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }

            state<AppState.Ready> {
                action<AppAction.TriggerPluginDispatch> {
                    // no-op
                }

                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        store.dispatch(AppAction.TriggerPluginDispatch)
        runCurrent()

        assertEquals(AppState.Ready(count = 1), store.currentState)

        store.dispatch(AppAction.Increment)
        runCurrent()

        launchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppState.Ready(count = 2), store.currentState)
        assertEquals(listOf("launch:Ready(count=2)"), records)
    }

    @Test
    fun pluginScope_shouldAllowDispatchFromHookBody() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val store = Store<AppState, AppAction, Nothing>(AppState.Loading) {
            coroutineContext(testDispatcher)
            plugin(
                object : Plugin<AppState, AppAction, Nothing> {
                    override suspend fun onAction(scope: PluginScope<AppState, AppAction>, state: AppState, action: AppAction) {
                        if (action == AppAction.TriggerPluginDispatch) {
                            scope.dispatch(AppAction.Increment)
                        }
                    }
                },
            )

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }

            state<AppState.Ready> {
                action<AppAction.TriggerPluginDispatch> {
                    // no-op
                }

                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        store.dispatch(AppAction.TriggerPluginDispatch)
        advanceUntilIdle()

        assertEquals(AppState.Ready(count = 1), store.currentState)
    }

    @Test
    fun pluginScopeLaunch_shouldRunFinallyOnStoreClose() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val records = mutableListOf<String>()

        val store = Store<AppState, AppAction, Nothing>(AppState.Loading) {
            coroutineContext(testDispatcher)
            plugin(
                object : Plugin<AppState, AppAction, Nothing> {
                    override suspend fun onStart(scope: PluginScope<AppState, AppAction>, state: AppState) {
                        scope.launch {
                            try {
                                kotlinx.coroutines.awaitCancellation()
                            } finally {
                                records += "cleanup:$currentState"
                            }
                        }
                    }
                },
            )

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }
        }

        store.dispatch(AppAction.Increment)
        runCurrent()

        store.close()
        advanceUntilIdle()

        assertEquals(listOf("cleanup:Ready(count=0)"), records)
    }

    @Test
    fun close_withoutPlugins_shouldNotInitializeStore() {
        val stateSaver = CountingStateSaver<AppState>(restoredState = AppState.Ready(count = 10))
        val store = Store<AppState, AppAction, Nothing>(AppState.Loading) {
            stateSaver(stateSaver)

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }
        }

        store.close()

        assertEquals(0, stateSaver.restoreCalls)
    }
}
