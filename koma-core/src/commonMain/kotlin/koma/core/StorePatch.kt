package koma.core

import kotlin.coroutines.CoroutineContext

data class StorePatch<S : State, A : Action, E : Event>(
    val initialState: S? = null,
    val coroutineContext: CoroutineContext? = null,
    val stateSaver: StateSaver<S>? = null,
    val exceptionHandler: ExceptionHandler? = null,
    val autoStartPolicy: AutoStartPolicy? = null,
    val pendingActionPolicy: PendingActionPolicy? = null,
    val pluginExecutionPolicy: PluginExecutionPolicy? = null,
    val pluginPatches: List<PluginPatch<S, A, E>> = emptyList(),
)

sealed interface PluginPatch<S : State, A : Action, E : Event> {
    data class Append<S : State, A : Action, E : Event>(val plugins: List<Plugin<S, A, E>>) : PluginPatch<S, A, E>
    data class Replace<S : State, A : Action, E : Event>(val plugins: List<Plugin<S, A, E>>) : PluginPatch<S, A, E>
    class Clear<S : State, A : Action, E : Event> : PluginPatch<S, A, E> {
        override fun equals(other: Any?): Boolean = other is Clear<*, *, *>
        override fun hashCode(): Int = 0
        override fun toString(): String = "Clear"
    }
}

/**
 * Builder used to construct a [StorePatch] via a DSL.
 *
 * This is the canonical builder shared by `:koma-test`'s public `Store.patch { ... }` extension
 * and Koma's own internal tests. It exposes Store configuration values that can be patched before
 * the Store has consumed them.
 */
@Suppress("unused")
class StorePatchBuilder<S : State, A : Action, E : Event> {
    private var initialStatePatch: S? = null
    private var coroutineContextPatch: CoroutineContext? = null
    private var stateSaverPatch: StateSaver<S>? = null
    private var exceptionHandlerPatch: ExceptionHandler? = null
    private var autoStartPolicyPatch: AutoStartPolicy? = null
    private var pendingActionPolicyPatch: PendingActionPolicy? = null
    private var pluginExecutionPolicyPatch: PluginExecutionPolicy? = null
    private val pluginPatches = mutableListOf<PluginPatch<S, A, E>>()

    fun initialState(state: S) {
        initialStatePatch = state
    }

    fun coroutineContext(coroutineContext: CoroutineContext) {
        coroutineContextPatch = coroutineContext
    }

    fun stateSaver(stateSaver: StateSaver<S>) {
        stateSaverPatch = stateSaver
    }

    fun exceptionHandler(exceptionHandler: ExceptionHandler) {
        exceptionHandlerPatch = exceptionHandler
    }

    fun autoStartPolicy(policy: AutoStartPolicy) {
        autoStartPolicyPatch = policy
    }

    fun pendingActionPolicy(policy: PendingActionPolicy) {
        pendingActionPolicyPatch = policy
    }

    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) {
        pluginExecutionPolicyPatch = policy
    }

    fun plugin(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) {
        pluginPatches += PluginPatch.Append(plugins = listOf(first) + rest)
    }

    fun replacePlugins(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) {
        pluginPatches += PluginPatch.Replace(plugins = listOf(first) + rest)
    }

    fun clearPlugins() {
        pluginPatches += PluginPatch.Clear()
    }

    fun build(): StorePatch<S, A, E> {
        return StorePatch(
            initialState = initialStatePatch,
            coroutineContext = coroutineContextPatch,
            stateSaver = stateSaverPatch,
            exceptionHandler = exceptionHandlerPatch,
            autoStartPolicy = autoStartPolicyPatch,
            pendingActionPolicy = pendingActionPolicyPatch,
            pluginExecutionPolicy = pluginExecutionPolicyPatch,
            pluginPatches = pluginPatches.toList(),
        )
    }
}
