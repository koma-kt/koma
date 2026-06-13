package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class StorePluginExceptionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val value: Int = 0) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Noop : AppAction
        data object Throw : AppAction
    }

    private fun createStore(
        plugin: Plugin<AppState, AppAction, Nothing>,
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
    ): Store<AppState, AppAction, Nothing> {
        return Store(AppState.Ready()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(exceptionHandler)
            plugin(plugin)

            state<AppState.Ready> {
                action<AppAction.Increment> {
                    nextState { state.copy(value = state.value + 1) }
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
    }

    @Test
    fun pluginException_isHandledWithoutUsingStoreErrorHandling() = runTest(testDispatcher) {
        var handledException: Throwable? = null
        val store = createStore(
            plugin = object : Plugin<AppState, AppAction, Nothing> {
                override suspend fun onAction(
                    scope: PluginScope<AppState, AppAction>,
                    state: AppState,
                    action: AppAction,
                ) {
                    throw IllegalArgumentException("plugin failed")
                }
            },
            exceptionHandler = ExceptionHandler { handledException = it },
        )

        store.dispatch(AppAction.Increment)
        testScheduler.runCurrent()

        assertEquals(AppState.Ready(), store.currentState)
        val error = assertIs<IllegalArgumentException>(handledException)
        assertEquals("plugin failed", error.message)
    }

    @Test
    fun pluginOnStateException_isHandledWithoutRetryingStoreErrorHandling() = runTest(testDispatcher) {
        var handledException: Throwable? = null
        val store = createStore(
            plugin = object : Plugin<AppState, AppAction, Nothing> {
                override suspend fun onState(
                    scope: PluginScope<AppState, AppAction>,
                    prevState: AppState,
                    state: AppState,
                ) {
                    throw IllegalArgumentException("onState failed")
                }
            },
            exceptionHandler = ExceptionHandler { handledException = it },
        )

        store.dispatch(AppAction.Increment)
        testScheduler.runCurrent()

        assertEquals(AppState.Ready(value = 1), store.currentState)
        val error = assertIs<IllegalArgumentException>(handledException)
        assertEquals("onState failed", error.message)
    }

    @Test
    fun pluginOnStartException_retriesInitializationOnNextDispatch() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val pluginRecords = mutableListOf<String>()
        var onStartCalls = 0
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)
            plugin(
                Plugin(
                    onStart = {
                        onStartCalls++
                        pluginRecords += "first"
                        throw IllegalStateException("start failed")
                    },
                ),
                Plugin(
                    onStart = {
                        pluginRecords += "second"
                    },
                ),
            )

            state<AppState.Loading> {
                enter {
                    nextState { AppState.Ready() }
                }
            }

            state<AppState.Ready> {
                action<AppAction.Noop> {}
            }
        }

        store.dispatch(AppAction.Noop)
        testScheduler.runCurrent()

        assertEquals(AppState.Loading, store.currentState)
        assertEquals(1, onStartCalls)
        assertEquals(listOf("first"), pluginRecords)
        val firstError = assertIs<IllegalStateException>(handled.single())
        assertEquals("start failed", firstError.message)

        store.dispatch(AppAction.Noop)
        testScheduler.runCurrent()

        assertEquals(AppState.Loading, store.currentState)
        assertEquals(2, onStartCalls)
        assertEquals(listOf("first", "first"), pluginRecords)
        val secondError = assertIs<IllegalStateException>(handled[1])
        assertEquals("start failed", secondError.message)
    }
}
