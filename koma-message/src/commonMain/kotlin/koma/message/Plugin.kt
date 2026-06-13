package koma.message

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginLaunchScope
import koma.core.PluginScope
import koma.core.State

/**
 * Creates a plugin that subscribes to Koma's shared message bus when the Store starts.
 *
 * The subscription stays active until the Store closes and invokes [block] for each received
 * [Message].
 * The underlying bus is process-wide and shared across all Stores using this plugin.
 * Messages are delivered only to active subscribers and are not replayed to Stores that start
 * later.
 *
 * @param block Function to process received messages with [PluginLaunchScope] as receiver
 * @return Plugin that processes shared messages
 */
fun <S : State, A : Action, E : Event> receiveMessages(block: suspend PluginLaunchScope<S, A>.(message: Message) -> Unit): Plugin<S, A, E> {
    return object : Plugin<S, A, E> {
        override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
            scope.launch {
                MessageHub.messages.collect { message ->
                    block(message)
                }
            }
        }
    }
}
