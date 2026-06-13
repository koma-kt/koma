package koma.core

/**
 * Controls how queued actions are handled when the store transitions to a different state variant.
 * Updates that stay within the same state variant are not affected.
 * For example, with a sealed interface such as `AppState`, `AppState.Loading ->
 * AppState.Loaded(...)` is affected, but `AppState.Loaded(items = emptyList()) ->
 * AppState.Loaded(items = listOf(item))` is not.
 */
enum class PendingActionPolicy {
    /**
     * Clears already queued actions after a transition to a different state variant is committed.
     * The currently running store work keeps running; only pending queued actions are discarded.
     * Dispatches queued before startup finishes are treated as post-start dispatches and are not
     * discarded by that startup transition, regardless of what triggered startup.
     */
    ClearOnStateExit,

    /**
     * Keeps queued actions across state-variant transitions unless they are cleared explicitly
     * from DSL scopes by calling clearPendingActions().
     */
    Keep,
}
