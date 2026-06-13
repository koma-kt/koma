package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class PluginBridgeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    class MessageDataSource {
        private val _dataFlow = MutableSharedFlow<String>()
        val dataFlow = _dataFlow.asSharedFlow()

        suspend fun simulateEmitMessage(message: String) {
            _dataFlow.emit(message)
        }
    }

    data class AppState(val message: String) : State

    sealed interface AppAction : Action {
        data class UpdateMessage(val message: String) : AppAction
    }

    @Test
    fun plugin_shouldHandleDataSourceEvents() = runTest(testDispatcher) {
        val messageDataSource = MessageDataSource()

        val store = createTestStore(messageDataSource)

        assertEquals(AppState(message = ""), store.currentState)

        messageDataSource.simulateEmitMessage("hello")
        assertEquals(AppState(message = ""), store.currentState)

        store.dispatch(AppAction.UpdateMessage("hello"))
        assertEquals(AppState(message = "hello"), store.currentState)

        messageDataSource.simulateEmitMessage("good bye")
        assertEquals(AppState(message = "good bye"), store.currentState)
    }

    private fun createTestStore(
        messageDataSource: MessageDataSource,
    ): Store<AppState, AppAction, Nothing> {
        return Store {
            initialState(AppState(message = ""))
            coroutineContext(Dispatchers.Unconfined)

            plugin(
                Plugin(
                    onStart = {
                        launch {
                            messageDataSource.dataFlow.collect { message ->
                                dispatch(AppAction.UpdateMessage(message = message))
                            }
                        }
                    },
                ),
            )

            state<AppState> {
                action<AppAction.UpdateMessage> {
                    nextState { state.copy(message = action.message) }
                }
            }
        }
    }
}
