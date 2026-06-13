package koma.core

/**
 * Controls how the Store invokes plugins when multiple plugins are registered.
 */
enum class PluginExecutionPolicy {
    /**
     * Invokes plugin hooks concurrently and waits until all of them complete.
     */
    Concurrent,

    /**
     * Invokes plugin hooks one by one in registration order.
     */
    InRegistrationOrder,
}
