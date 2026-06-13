package koma.logging

import koma.core.Action
import koma.core.Plugin
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoggingPluginTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun loggingPlugin_shouldLogAction() = runTest(testDispatcher) {
        val testLogger = TestLogger()
        val plugin = simpleLogging<CounterState, CounterAction, Nothing>(logger = testLogger)

        val store = createTestStore(CounterState(10), plugin)

        store.dispatch(CounterAction.Increment)

        assertTrue(testLogger.logs.isNotEmpty())
        assertTrue(testLogger.logs.any { it.contains("Action:") })
    }
}

private data class CounterState(val count: Int) : State

private sealed interface CounterAction : Action {
    data object Increment : CounterAction
    data object Decrement : CounterAction
}

private class TestLogger : Logger {
    val logs = mutableListOf<String>()
    override fun log(severity: Logger.Severity, tag: String, throwable: Throwable?, message: () -> String) {
        logs.add(message())
    }
}

private fun createTestStore(
    initialState: CounterState,
    plugin: Plugin<CounterState, CounterAction, Nothing>,
): Store<CounterState, CounterAction, Nothing> {
    return Store(initialState) {
        coroutineContext(Dispatchers.Unconfined)
        plugin(plugin)
        state<CounterState> {
            action<CounterAction.Increment> {
                nextState(state.copy(count = state.count + 1))
            }
            action<CounterAction.Decrement> {
                nextState(state.copy(count = state.count - 1))
            }
        }
    }
}
