package koma.logging

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.State
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates a plugin that logs actions, events, and committed state changes.
 *
 * @param tag The tag to use for logging
 * @param severity The severity level for log messages
 * @param logger The logger implementation to use
 * @param dispatcher Optional CoroutineDispatcher override for logging operations
 * @return Plugin that logs common Store operations
 */
fun <S : State, A : Action, E : Event> simpleLogging(
    tag: String = "Koma",
    severity: Logger.Severity = Logger.Severity.Debug,
    logger: Logger = DefaultLogger,
    dispatcher: CoroutineDispatcher? = null,
): Plugin<S, A, E> {
    // launch coroutines to avoid blocking Store processing in case of heavy logging
    return object : Plugin<S, A, E> {
        override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
            scope.launch(dispatcher) {
                logger.log(severity = severity, tag = tag, throwable = null) { "Action: $action" }
            }
        }

        override suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {
            scope.launch(dispatcher) {
                logger.log(severity = severity, tag = tag, throwable = null) { "Event: $event" }
            }
        }

        override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
            scope.launch(dispatcher) {
                logger.log(severity = severity, tag = tag, throwable = null) { "State: $state <- $prevState" }
            }
        }
    }
}
