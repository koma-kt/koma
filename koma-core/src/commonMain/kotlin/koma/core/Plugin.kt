package koma.core

/**
 * Extension point for reacting to Store lifecycle events such as startup, dispatched actions,
 * committed state snapshots and emitted events.
 *
 * Plugin hooks are suspending functions and are awaited by the Store according to the configured
 * [PluginExecutionPolicy] before it continues processing. Long-running work in a hook can delay
 * Store processing. When work should continue in the background, start it from a hook using
 * [PluginScope.launch].
 */
interface Plugin<S : State, A : Action, E : Event> {
    /**
     * Called once when Store startup begins, before the initial `enter {}` processing starts.
     */
    suspend fun onStart(scope: PluginScope<S, A>, state: S) {}

    /**
     * Called before an action handler starts processing the dispatched action.
     */
    suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {}

    /**
     * Called after a new state snapshot is committed, persisted, and reported to observers.
     */
    suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {}

    /**
     * Called after an event is emitted to collectors and observers.
     */
    suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {}
}

/**
 * Creates a [Plugin] from individual lifecycle callbacks.
 *
 * These callbacks are suspending and are awaited by the Store just like a custom
 * [Plugin] implementation.
 *
 * All callbacks use [PluginScope] as the receiver so they can start Store-scoped background
 * work without having to capture the scope manually.
 * Additional capabilities such as reading the latest committed state snapshot or dispatching
 * actions are available only inside [PluginScope.launch].
 *
 * @param onStart Function called when Store startup begins, before the initial `enter {}`
 * processing starts
 * @param onAction Function called before an action handler starts
 * @param onState Function called after a state snapshot is committed and persisted
 * @param onEvent Function called after an event is emitted
 */
fun <S : State, A : Action, E : Event> Plugin(
    onStart: suspend PluginScope<S, A>.(state: S) -> Unit = {},
    onAction: suspend PluginScope<S, A>.(state: S, action: A) -> Unit = { _, _ -> },
    onState: suspend PluginScope<S, A>.(prevState: S, state: S) -> Unit = { _, _ -> },
    onEvent: suspend PluginScope<S, A>.(state: S, event: E) -> Unit = { _, _ -> },
) = object : Plugin<S, A, E> {
    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        scope.onStart(state)
    }

    override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
        scope.onAction(state, action)
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        scope.onState(prevState, state)
    }

    override suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {
        scope.onEvent(state, event)
    }
}
