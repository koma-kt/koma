package koma.message

import koma.core.StoreScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Marker interface for process-wide messages sent through Koma's shared message bus.
 */
interface Message

internal object MessageHub {
    private val _messages = MutableSharedFlow<Message>()
    val messages: Flow<Message> get() = _messages

    suspend fun send(message: Message) {
        _messages.emit(message)
    }
}

/**
 * Sends a [Message] to Koma's process-wide shared message bus.
 *
 * Any DSL scope that implements [StoreScope] can call this, including enter, action, exit, recover,
 * launch, and transaction scopes.
 * Messages are not replayed, so receivers that are not actively collecting when a message is sent
 * will not receive that past message.
 *
 * @param message The message to send
 */
suspend fun StoreScope.message(message: Message) {
    MessageHub.send(message)
}
