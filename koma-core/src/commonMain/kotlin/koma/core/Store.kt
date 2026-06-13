package koma.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core Koma interface for reading state, dispatching actions, and observing one-off events.
 *
 * Store startup is lazy.
 * Startup processing begins when [start] is called, when an action is [dispatch]ed, and,
 * with [AutoStartPolicy.OnDispatchOrStateCollection], when [state] starts being collected
 * or [collectState] is called.
 * Collecting [event], calling [collectEvent], or reading [currentState] alone does not start the
 * Store.
 */
interface Store<S : State, A : Action, E : Event> : AutoCloseable {

    /**
     * Hot stream of committed state snapshots.
     *
     * Collecting this flow starts the Store if it has not started yet and the configured
     * [AutoStartPolicy] includes state collection as a trigger.
     */
    val state: StateFlow<S>

    /**
     * Hot stream of one-off events emitted by the Store.
     *
     * Collecting this flow does not start the Store by itself.
     * Past events are not replayed to new collectors.
     */
    val event: Flow<E>

    /**
     * Current state snapshot without collecting [state].
     *
     * Reading this property does not start the Store.
     * If a [StateSaver] restores a saved state, this may return that restored snapshot
     * even before startup processing begins.
     */
    val currentState: S

    /**
     * Starts the Store if it has not started yet.
     *
     * This method returns immediately after requesting startup processing.
     * It does not wait for startup to finish.
     * Calling this method more than once has no additional effect after the first startup begins.
     */
    fun start()

    /**
     * Enqueues an action for processing.
     *
     * This enqueues the action and returns immediately.
     * It does not wait for action handling to complete.
     * If the Store has not started yet, this also triggers startup processing before the action
     * runs.
     *
     * @param action The action to dispatch
     */
    fun dispatch(action: A)

    /**
     * Collects committed state snapshots using a callback.
     *
     * This API is intended for platforms where [StateFlow] cannot be consumed directly.
     * Calling this method starts the Store if it has not started yet and the configured
     * [AutoStartPolicy] includes state collection as a trigger.
     *
     * The callback runs in the Store's execution context.
     * Koma does not switch to a UI thread automatically and does not guarantee delivery
     * on the thread that called [collectState].
     * If your platform requires UI-thread access, move to the appropriate UI thread
     * before touching UI components from the callback.
     *
     * Collection continues until the Store is closed.
     *
     * @param state Callback called when the state changes
     */
    fun collectState(state: (state: S) -> Unit)

    /**
     * Collects one-off events using a callback.
     *
     * This API is intended for platforms where [Flow] cannot be consumed directly.
     * Calling this method does not start the Store by itself.
     * If you need startup processing to run, also trigger startup through [start], [dispatch],
     * [state], or [collectState].
     *
     * The callback runs in the Store's execution context.
     * Koma does not switch to a UI thread automatically and does not guarantee delivery
     * on the thread that called [collectEvent].
     * If your platform requires UI-thread access, move to the appropriate UI thread
     * before touching UI components from the callback.
     *
     * Collection continues until the Store is closed.
     * Events emitted before collection starts are not replayed.
     *
     * @param event Callback called when an event is emitted
     */
    fun collectEvent(event: (event: E) -> Unit)

    /**
     * Cancels the Store and releases its resources.
     *
     * Active state-scoped coroutines, plugin coroutines, and callback collectors are cancelled.
     */
    override fun close()

    /**
     * Releases the Store's resources.
     *
     * Kept for backward compatibility. Use [close] instead.
     */
    @Deprecated(message = "Use close()", replaceWith = ReplaceWith("close()"))
    fun dispose() {
        close()
    }
}
