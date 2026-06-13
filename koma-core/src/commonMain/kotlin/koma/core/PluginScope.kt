package koma.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Scope exposed to [Plugin] hooks.
 *
 * Plugins can use this scope to start Store-scoped background work.
 */
@KomaStoreDsl
interface PluginScope<S : State, A : Action> {
    /**
     * Dispatches an action to the Store.
     *
     * This enqueues the action and returns immediately.
     * It does not wait for action handling to complete.
     */
    fun dispatch(action: A)

    /**
     * Starts background work in the Store's root coroutine scope and returns immediately.
     *
     * The launched coroutine survives state changes and is cancelled when the Store's root
     * coroutine scope is cancelled, such as by [Store.close] or parent scope cancellation.
     */
    fun launch(dispatcher: CoroutineDispatcher? = null, block: suspend PluginLaunchScope<S, A>.() -> Unit)

    /**
     * Scope available within background work launched from a [Plugin].
     */
    @KomaStoreDsl
    interface LaunchScope<S : State, A : Action> {
        /**
         * The latest committed state snapshot.
         *
         * This value may change immediately after it is read if other Store work commits a new state.
         */
        val currentState: S

        /**
         * Dispatches an action to the Store.
         *
         * This enqueues the action and returns immediately.
         * It does not wait for action handling to complete.
         */
        fun dispatch(action: A)
    }
}

/**
 * Flattened alias for [PluginScope.LaunchScope] to keep public signatures concise in IDE tooltips.
 */
typealias PluginLaunchScope<S, A> = PluginScope.LaunchScope<S, A>
