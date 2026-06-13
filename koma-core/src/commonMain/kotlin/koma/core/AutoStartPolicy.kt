package koma.core

/**
 * Controls which actions implicitly start a lazy Store.
 */
enum class AutoStartPolicy {
    /**
     * Starts the Store when an action is dispatched or when state collection begins.
     *
     * This is the default behavior.
     */
    OnDispatchOrStateCollection,

    /**
     * Starts the Store when an action is dispatched.
     *
     * Collecting state alone does not start the Store. Use [Store.start] when you want to begin
     * startup processing explicitly before the first dispatch.
     */
    OnDispatch,
}
