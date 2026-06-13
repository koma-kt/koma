package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import koma.core.StoreInternalApi
import koma.core.StorePatchBuilder

/**
 * Starts the Store and suspends until the startup work completes.
 *
 * Prefer this for tests that need to observe startup behavior before the first dispatched action.
 *
 * This waits for plugin `onStart` hooks and the synchronous `enter {}` chain triggered by startup.
 * It does not wait for additional work launched from `enter {}` handlers.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.startAndAwait() {
    requireStoreInternalApi().startAndAwait()
}

/**
 * Dispatches an action and suspends until the Store finishes the dispatch work itself.
 *
 * This waits for startup when needed, the matching action handler, and any resulting synchronous
 * state transition work triggered by that dispatch.
 * It does not wait for additional work launched from `enter {}` or `action {}` handlers.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param action The action to dispatch
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndAwait(action: A) {
    requireStoreInternalApi().dispatchAndAwait(action)
}

/**
 * Applies a Store configuration patch before the Store is started.
 *
 * This is intended for tests that need to swap persistence, policies, exception handling,
 * plugins, or the declared initial state without rewriting the Store definition itself.
 * The patch must happen before startup processing begins. Some values must also be patched before
 * they are consumed: `initialState` and `stateSaver` before state is read, and `coroutineContext`
 * before the Store launches any coroutine.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param builder Builder lambda for a Store configuration patch
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the patch targets a value that has already been consumed
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
fun <S : State, A : Action, E : Event> Store<S, A, E>.patch(
    builder: StorePatchBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> {
    val patch = StorePatchBuilder<S, A, E>().apply(builder).build()
    return requireStoreInternalApi().patch(patch)
}

@OptIn(InternalKomaApi::class)
private fun <S : State, A : Action, E : Event> Store<S, A, E>.requireStoreInternalApi(): StoreInternalApi<S, A, E> {
    @Suppress("UNCHECKED_CAST")
    return this as? StoreInternalApi<S, A, E>
        ?: throw IllegalStateException("[Koma] This API is only supported for Store instances created by Koma DSL")
}
