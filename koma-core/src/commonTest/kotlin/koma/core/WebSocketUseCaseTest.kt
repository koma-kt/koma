package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌───────────────────────────────────────────────────┐
// │                                                   │
// │            Disconnected       Error               │
// │                 ▲               ▲                 │
// │                 │               │                 │
// │                 │   ┌───────────┘                 │
// │                 │   │                             │
// │                 ▼   ▼                             │
// │  ┌─────────────────────────────────────────────┐  │
// │  │              Connected                      │  │
// │  │                                             │  │
// │  │   Status: Connecting/Connected/Disconnected │  │
// │  │   Messages: Received real-time updates      │  │
// │  │                                             │  │
// │  └─────────────────────────────────────────────┘  │
// │                                                   │
// └───────────────────────────────────────────────────┘
//
// Disconnected: Initial state, no connection to WebSocket server
// Connected: Managing WebSocket connection with different internal statuses
// Error: Error state for handling connection failures

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebSocketUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Domain models
    data class WebSocketMessage(
        val id: String,
        val content: String,
        val timestamp: Long,
    )

    // WebSocket service interfaces
    interface WebSocketService {
        fun connect()
        fun disconnect()
        val connectionEvents: Flow<ConnectionEvent>
        val messages: Flow<WebSocketMessage>

        sealed interface ConnectionEvent {
            data object Connected : ConnectionEvent
            data object Disconnected : ConnectionEvent
            data class Error(val throwable: Throwable) : ConnectionEvent
        }
    }

    // Mock implementation for testing
    class MockWebSocketService : WebSocketService {
        private val connectionChannel = Channel<WebSocketService.ConnectionEvent>(Channel.BUFFERED)
        private val messageChannel = Channel<WebSocketMessage>(Channel.BUFFERED)

        override val connectionEvents: Flow<WebSocketService.ConnectionEvent> = connectionChannel.receiveAsFlow()
        override val messages: Flow<WebSocketMessage> = messageChannel.receiveAsFlow()

        override fun connect() {
            // Connection logic happens in simulation methods for testing
        }

        override fun disconnect() {
            // In real implementation, this would trigger the actual disconnect
            // The event itself is sent by simulateConnectionClosed() for testing
        }

        fun simulateConnectionEstablished() {
            connectionChannel.trySend(WebSocketService.ConnectionEvent.Connected)
        }

        fun simulateConnectionError(error: Throwable) {
            connectionChannel.trySend(WebSocketService.ConnectionEvent.Error(error))
        }

        fun simulateConnectionClosed() {
            connectionChannel.trySend(WebSocketService.ConnectionEvent.Disconnected)
        }

        fun simulateMessageReceived(message: WebSocketMessage) {
            messageChannel.trySend(message)
        }
    }

    // Koma State definitions
    sealed interface AppState : State {
        data object Disconnected : AppState

        data class Connected(
            val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
            val latestMessage: WebSocketMessage? = null,
        ) : AppState

        data class Error(val error: Throwable) : AppState

        enum class ConnectionStatus {
            CONNECTING, CONNECTED, DISCONNECTING
        }
    }

    // Koma Action definitions
    sealed interface AppAction : Action {
        data object Connect : AppAction
        data object Disconnect : AppAction
    }

    private fun createTestStore(
        webSocketService: WebSocketService,
    ): Store<AppState, AppAction, Nothing> {
        return Store {
            initialState(AppState.Disconnected)
            coroutineContext(Dispatchers.Unconfined)

            state<AppState.Disconnected> {
                action<AppAction.Connect> {
                    nextState { AppState.Connected(connectionStatus = AppState.ConnectionStatus.CONNECTING) }
                }
            }

            state<AppState.Connected> {
                enter {
                    // Skip if not in CONNECTING status (early return pattern)
                    if (state.connectionStatus != AppState.ConnectionStatus.CONNECTING) {
                        return@enter
                    }

                    // Initiate connection
                    webSocketService.connect()

                    // Monitor both connection events and messages in a single state
                    launch {
                        webSocketService.connectionEvents.collect { event ->
                            when (event) {
                                is WebSocketService.ConnectionEvent.Connected -> {
                                    transaction {
                                        nextState { state.copy(connectionStatus = AppState.ConnectionStatus.CONNECTED) }
                                    }
                                }

                                is WebSocketService.ConnectionEvent.Error -> {
                                    transaction {
                                        nextState { AppState.Error(event.throwable) }
                                    }
                                }

                                is WebSocketService.ConnectionEvent.Disconnected -> {
                                    transaction {
                                        nextState { AppState.Disconnected }
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        webSocketService.messages.collect { message ->
                            transaction {
                                // Only update messages if in CONNECTED status
                                if (state.connectionStatus == AppState.ConnectionStatus.CONNECTED) {
                                    nextState { state.copy(latestMessage = message) }
                                }
                                // Otherwise ignore messages that arrive before fully connected
                                // or after disconnecting has started
                            }
                        }
                    }
                }

                action<AppAction.Disconnect> {
                    // First update state to disconnecting
                    nextState { state.copy(connectionStatus = AppState.ConnectionStatus.DISCONNECTING) }

                    // Then initiate disconnect
                    webSocketService.disconnect()
                    // Actual state change to Disconnected will happen through the event flow
                }
            }

            state<AppState.Error> {
                action<AppAction.Connect> {
                    nextState { AppState.Connected(connectionStatus = AppState.ConnectionStatus.CONNECTING) }
                }
            }
        }
    }

    @Test
    fun webSocket_shouldConnectAndHandleMessages() = runTest(testDispatcher) {
        // Mock implementation of WebSocket service
        val mockWebSocket = MockWebSocketService()

        // Create store with disconnected state
        val store = createTestStore(mockWebSocket)

        // Initial state should be Disconnected
        assertIs<AppState.Disconnected>(store.currentState)

        // Connect to WebSocket
        store.dispatch(AppAction.Connect)

        // State should change to Connected with CONNECTING status
        val connectingState = store.currentState
        assertIs<AppState.Connected>(connectingState)
        assertEquals(AppState.ConnectionStatus.CONNECTING, connectingState.connectionStatus)

        // Simulate connection success
        mockWebSocket.simulateConnectionEstablished()

        // State should change to Connected with CONNECTED status
        val connectedState = store.currentState
        assertIs<AppState.Connected>(connectedState)
        assertEquals(AppState.ConnectionStatus.CONNECTED, connectedState.connectionStatus)

        // Simulate receiving a message
        val testMessage = WebSocketMessage("test-id", "Test message", 1234567890L) // Using fixed timestamp for test
        mockWebSocket.simulateMessageReceived(testMessage)

        // State should update with the latest message
        val stateWithMessage = store.currentState
        assertIs<AppState.Connected>(stateWithMessage)
        assertEquals(testMessage, stateWithMessage.latestMessage)
        assertEquals(AppState.ConnectionStatus.CONNECTED, stateWithMessage.connectionStatus)

        // Disconnect
        store.dispatch(AppAction.Disconnect)

        // State should first change to DISCONNECTING
        val disconnectingState = store.currentState
        assertIs<AppState.Connected>(disconnectingState)
        assertEquals(AppState.ConnectionStatus.DISCONNECTING, disconnectingState.connectionStatus)

        // After the disconnect event is processed, state should return to Disconnected
        mockWebSocket.simulateConnectionClosed()

        // State should return to Disconnected
        assertIs<AppState.Disconnected>(store.currentState)
    }

    @Test
    fun webSocket_shouldHandleConnectionFailures() = runTest(testDispatcher) {
        // Mock implementation of WebSocket service
        val mockWebSocket = MockWebSocketService()

        // Create store with disconnected state
        val store = createTestStore(mockWebSocket)

        // Connect to WebSocket
        store.dispatch(AppAction.Connect)

        // Verify we're in CONNECTING status
        val connectingState = store.currentState
        assertIs<AppState.Connected>(connectingState)
        assertEquals(AppState.ConnectionStatus.CONNECTING, connectingState.connectionStatus)

        // Simulate connection failure
        val error = Exception("Connection refused")
        mockWebSocket.simulateConnectionError(error)

        // State should change to Error
        val errorState = store.currentState
        assertIs<AppState.Error>(errorState)
        assertEquals(error, errorState.error)
    }

    @Test
    fun webSocket_shouldHandleDisconnectDuringConnecting() = runTest(testDispatcher) {
        // Mock implementation of WebSocket service
        val mockWebSocket = MockWebSocketService()

        // Create store with disconnected state
        val store = createTestStore(mockWebSocket)

        // Connect to WebSocket
        store.dispatch(AppAction.Connect)

        // Verify we're in CONNECTING status
        val connectingState = store.currentState
        assertIs<AppState.Connected>(connectingState)
        assertEquals(AppState.ConnectionStatus.CONNECTING, connectingState.connectionStatus)

        // Disconnect while still connecting
        store.dispatch(AppAction.Disconnect)

        // State should first change to DISCONNECTING
        val disconnectingState = store.currentState
        assertIs<AppState.Connected>(disconnectingState)
        assertEquals(AppState.ConnectionStatus.DISCONNECTING, disconnectingState.connectionStatus)

        // After the disconnect event is processed, state should return to Disconnected
        mockWebSocket.simulateConnectionClosed()

        assertIs<AppState.Disconnected>(store.currentState)
    }
}
