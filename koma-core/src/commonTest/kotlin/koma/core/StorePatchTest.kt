package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StorePatchTest {

    data class AppState(val count: Int) : State

    sealed interface AppAction : Action {
        data object Increment : AppAction
    }

    sealed interface PendingPolicyState : State {
        data object Initial : PendingPolicyState
        data class Active(val value: Int = 0) : PendingPolicyState
    }

    sealed interface PendingPolicyAction : Action {
        data object EnterActiveAfterDelay : PendingPolicyAction
        data object Increment : PendingPolicyAction
    }

    private class RecordingStateSaver(
        private val restoredState: AppState?,
    ) : StateSaver<AppState> {
        val savedStates = mutableListOf<AppState>()

        override fun save(state: AppState) {
            savedStates += state
        }

        override fun restore(): AppState? = restoredState
    }

    private fun recordingPlugin(
        name: String,
        records: MutableList<String>,
    ): Plugin<AppState, AppAction, Nothing> {
        return Plugin(
            onAction = { _, _ ->
                records += name
            },
        )
    }

    @Test
    fun storeInitialStateOverload_shouldApplyPatchAfterSetup() {
        val setupSaver = RecordingStateSaver(restoredState = AppState(count = 100))
        val configuredSaver = RecordingStateSaver(restoredState = AppState(count = 10))
        val pluginRecords = mutableListOf<String>()

        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            stateSaver(setupSaver)
            plugin(recordingPlugin("setup", pluginRecords))

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(configuredSaver)
            replacePlugins(recordingPlugin("override", pluginRecords))
        }

        assertEquals(AppState(count = 10), store.currentState)

        store.dispatch(AppAction.Increment)

        assertEquals(listOf("override"), pluginRecords)
        assertEquals(listOf(AppState(count = 11)), configuredSaver.savedStates)
        assertTrue(setupSaver.savedStates.isEmpty())
    }

    @Test
    fun storeDslInitialStateOverload_shouldApplyPatchAndAllowPluginAppendAfterReplacement() {
        val setupSaver = RecordingStateSaver(restoredState = AppState(count = 100))
        val configuredSaver = RecordingStateSaver(restoredState = AppState(count = 20))
        val pluginRecords = mutableListOf<String>()

        val store: Store<AppState, AppAction, Nothing> = Store {
            initialState(AppState(count = 0))
            stateSaver(setupSaver)
            plugin(recordingPlugin("setup", pluginRecords))

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(configuredSaver)
            replacePlugins(recordingPlugin("replacement", pluginRecords))
            plugin(recordingPlugin("extra", pluginRecords))
        }

        assertEquals(AppState(count = 20), store.currentState)

        store.dispatch(AppAction.Increment)

        assertEquals(listOf("extra", "replacement"), pluginRecords.sorted())
        assertEquals(listOf(AppState(count = 21)), configuredSaver.savedStates)
        assertTrue(setupSaver.savedStates.isEmpty())
    }

    @Test
    fun clearPluginsInPatch_shouldClearPreviouslyConfiguredPlugins() {
        val pluginRecords = mutableListOf<String>()

        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(recordingPlugin("setup", pluginRecords))

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            clearPlugins()
        }

        store.dispatch(AppAction.Increment)

        assertTrue(pluginRecords.isEmpty())
    }

    @Test
    fun plugin_shouldAcceptMultipleValuesInSetupAndPatch() {
        val pluginRecords = mutableListOf<String>()

        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(
                recordingPlugin("setup1", pluginRecords),
                recordingPlugin("setup2", pluginRecords),
            )

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            plugin(
                recordingPlugin("override1", pluginRecords),
                recordingPlugin("override2", pluginRecords),
            )
        }

        store.dispatch(AppAction.Increment)

        assertEquals(
            listOf("setup1", "setup2", "override1", "override2"),
            pluginRecords,
        )
    }

    @Test
    fun patch_shouldOverridePendingActionPolicyFromBuilder() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = Store<PendingPolicyState, PendingPolicyAction, Nothing>(initialState = PendingPolicyState.Initial) {
            coroutineContext(testDispatcher)
            pendingActionPolicy(PendingActionPolicy.ClearOnStateExit)

            state<PendingPolicyState.Initial> {
                action<PendingPolicyAction.EnterActiveAfterDelay>(testDispatcher) {
                    delay(100)
                    nextState { PendingPolicyState.Active() }
                }
            }

            state<PendingPolicyState.Active> {
                action<PendingPolicyAction.Increment>(testDispatcher) {
                    nextState { state.copy(value = state.value + 1) }
                }
            }
        }.patchForTest {
            pendingActionPolicy(PendingActionPolicy.Keep)
        }

        store.dispatch(PendingPolicyAction.EnterActiveAfterDelay)
        runCurrent()

        store.dispatch(PendingPolicyAction.Increment)
        store.dispatch(PendingPolicyAction.Increment)

        advanceUntilIdle()

        assertEquals(PendingPolicyState.Active(value = 2), store.currentState)
    }

    @Test
    fun patch_shouldOverrideAutoStartPolicyFromBuilder() {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)
            autoStartPolicy(AutoStartPolicy.OnDispatchOrStateCollection)

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            autoStartPolicy(AutoStartPolicy.OnDispatch)
        }

        store.collectState { }

        assertEquals(AppState(count = 0), store.currentState)

        store.dispatch(AppAction.Increment)

        assertEquals(AppState(count = 1), store.currentState)
    }

    @Test
    fun patch_shouldOverrideInitialStateFromBuilder() {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }.patchForTest {
            initialState(AppState(count = 42))
        }

        assertEquals(AppState(count = 42), store.currentState)
    }

    @Test
    fun patch_shouldRejectInitialStateChangeAfterCurrentStateIsRead() {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }

        assertEquals(AppState(count = 0), store.currentState)

        assertFailsWith<IllegalStateException> {
            store.patchForTest {
                initialState(AppState(count = 42))
            }
        }
    }

    @Test
    fun patch_shouldRejectStateSaverChangeAfterCurrentStateIsRead() {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }

        assertEquals(AppState(count = 0), store.currentState)

        assertFailsWith<IllegalStateException> {
            store.patchForTest {
                stateSaver(RecordingStateSaver(restoredState = null))
            }
        }
    }

    @Test
    fun patch_shouldAllowOtherChangesAfterCurrentStateIsRead() {
        val pluginRecords = mutableListOf<String>()
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }

        assertEquals(AppState(count = 0), store.currentState)

        store.patchForTest {
            exceptionHandler(ExceptionHandler.Log)
            plugin(recordingPlugin("late", pluginRecords))
        }

        store.dispatch(AppAction.Increment)

        assertEquals(listOf("late"), pluginRecords)
    }

    @Test
    fun patch_shouldRejectCoroutineContextChangeAfterCollectEvent() {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }

        store.collectEvent { }

        assertFailsWith<IllegalStateException> {
            store.patchForTest {
                coroutineContext(Dispatchers.Unconfined)
            }
        }
    }

    @Test
    fun patch_shouldAllowOtherChangesAfterCollectEvent() {
        val pluginRecords = mutableListOf<String>()
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState(count = 0)) {
            coroutineContext(Dispatchers.Unconfined)

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { AppState(count = state.count + 1) }
                }
            }
        }

        store.collectEvent { }

        store.patchForTest {
            exceptionHandler(ExceptionHandler.Log)
            plugin(recordingPlugin("late", pluginRecords))
        }

        store.dispatch(AppAction.Increment)

        assertEquals(listOf("late"), pluginRecords)
    }
}
