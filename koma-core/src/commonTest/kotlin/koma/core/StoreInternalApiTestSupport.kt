@file:OptIn(InternalKomaApi::class)

package koma.core

internal fun <S : State, A : Action, E : Event> Store<S, A, E>.patchForTest(
    block: StorePatchBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> {
    val patch = StorePatchBuilder<S, A, E>().apply(block).build()
    return requireStoreInternalApi().patch(patch)
}

internal suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndAwaitForTest(action: A) {
    requireStoreInternalApi().dispatchAndAwait(action)
}

internal suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.startAndAwaitForTest() {
    requireStoreInternalApi().startAndAwait()
}

@Suppress("UNCHECKED_CAST")
private fun <S : State, A : Action, E : Event> Store<S, A, E>.requireStoreInternalApi(): StoreInternalApi<S, A, E> {
    return this as? StoreInternalApi<S, A, E>
        ?: error("Expected Koma Store to implement StoreInternalApi")
}
