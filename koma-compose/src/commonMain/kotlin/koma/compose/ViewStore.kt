package koma.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.retain
import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import androidx.compose.runtime.State as ComposeState

/**
 * Compose-friendly state holder for a [Store].
 *
 * A [ViewStore] is tied to a Store instance and exposes its latest state, a dispatch function,
 * and access to the Store's event stream.
 *
 * When created by [rememberViewStore], the same [ViewStore] instance is retained while the
 * underlying Store instance remains the same. Its [state] property always reads the latest
 * collected Store state from Compose-managed state.
 *
 * The secondary constructor that accepts a plain [state] creates a standalone holder with a fixed
 * initial value, which is useful for previews, tests, and other non-Store-backed usage.
 *
 * @param stateRef Compose state that always holds the latest Store state
 * @param dispatch Function to dispatch actions
 * @param eventFlow Flow used to receive one-off events
 */
@Stable
class ViewStore<S : State, A : Action, E : Event> internal constructor(
    private val stateRef: ComposeState<S>,
    val dispatch: (action: A) -> Unit,
    @PublishedApi internal val eventFlow: Flow<E>,
) {
    // NOTE: Keep the dispatch and eventFlow arguments until test support is implemented.
    constructor(
        state: S,
        dispatch: (A) -> Unit = {},
        eventFlow: Flow<E> = emptyFlow(),
    ) : this(
        stateRef = mutableStateOf(state),
        dispatch = dispatch,
        eventFlow = eventFlow,
    )

    val state: S
        get() = stateRef.value

    /**
     * Invokes [block] only when the current [state] is of type [S2].
     *
     * Inside [block], this [ViewStore] is narrowed to [S2].
     *
     * @param block Composable function to render content for the narrowed state
     */
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified S2 : S> stateContent(block: @Composable ViewStore<S2, A, E>.() -> Unit) {
        if (state is S2) {
            @Suppress("UNCHECKED_CAST")
            block(this as ViewStore<S2, A, E>)
        }
    }

    @Deprecated(
        message = "Use stateContent instead.",
        replaceWith = ReplaceWith("stateContent<S2>(block)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified S2 : S> render(block: @Composable ViewStore<S2, A, E>.() -> Unit) = stateContent<S2>(block)

    /**
     * Collects only events of type [E2] while this composable is in the composition.
     *
     * Collection starts after the composable enters the composition.
     * Events emitted earlier are not replayed.
     *
     * @param block Function to process the event
     */
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified E2 : E> eventEffect(noinline block: ViewStore<S, A, E>.(event: E2) -> Unit) {
        val currentViewStore = rememberUpdatedState(this)
        val currentBlock = rememberUpdatedState(block)
        LaunchedEffect(eventFlow) {
            eventFlow.filterIsInstance<E2>().collect {
                currentBlock.value(currentViewStore.value, it)
            }
        }
    }

    @Deprecated(
        message = "Use eventEffect instead.",
        replaceWith = ReplaceWith("eventEffect<E2>(block)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("ComposableNaming", "unused")
    @Composable
    inline fun <reified E2 : E> handle(noinline block: ViewStore<S, A, E>.(event: E2) -> Unit) = eventEffect<E2>(block)
}

/**
 * Remembers a [Store], collects its state as Compose state, and exposes it through a [ViewStore].
 *
 * Use this overload when a Store is already provided by a ViewModel or dependency injection.
 * For a given Store instance, this function returns the same [ViewStore] instance across
 * recompositions. A new [ViewStore] is created only when [store] changes.
 *
 * When [autoClose] is true, the Store is closed when the composable leaves the composition.
 * Otherwise, its owner is responsible for closing it. The [autoClose] value is fixed when the
 * Store instance is adopted; recomposition alone does not change it for the same Store.
 *
 * @param store Source Store instance
 * @param autoClose Whether to close the Store when the composable leaves the composition;
 * defaults to false
 * @return A ViewStore state holder backed by the Store
 */
@Composable
fun <S : State, A : Action, E : Event> rememberViewStore(store: Store<S, A, E>, autoClose: Boolean = false): ViewStore<S, A, E> {
    return rememberViewStoreInternal(store, autoClose)
}

/**
 * Remembers a [Store], collects its state as Compose state, and exposes it through a [ViewStore].
 *
 * The [store] lambda is used only when a new remembered Store must be created for [key].
 * For a given remembered Store instance, this function returns the same [ViewStore] instance across
 * recompositions. A new [ViewStore] is created only when a new Store instance is remembered for
 * [key].
 * The [autoClose] value is fixed when that remembered Store instance is adopted. Recomposition
 * alone does not change close ownership for the same Store, but when [key] causes a different
 * Store instance to be remembered, the current [autoClose] value is used for that new Store.
 *
 * Prefer [retainViewStore] for creating a Store owned by the UI. It retains the Store across
 * transient composition recreation and always closes it when retention ends.
 *
 * @param key Key used to remember and retain the Store instance
 * @param autoClose Whether to close the Store when the composable leaves the composition
 * @param store Function to create the source Store instance
 * @return A ViewStore state holder backed by the remembered Store
 */
@Deprecated(
    message = "Use retainViewStore instead. The Store is retained across transient composition recreation and is always closed when retention ends.",
    replaceWith = ReplaceWith("retainViewStore(key, store = store)"),
    level = DeprecationLevel.WARNING,
)
@Composable
fun <S : State, A : Action, E : Event> rememberViewStore(key: Any? = null, autoClose: Boolean = false, store: () -> Store<S, A, E>): ViewStore<S, A, E> {
    val rememberedStore = remember(key) { store() }
    return rememberViewStoreInternal(rememberedStore, autoClose)
}

/**
 * Retains a [Store] across transient composition recreation and exposes its state as a [ViewStore].
 *
 * The [store] factory runs only when no retained Store is available for [keys]. Retention follows
 * `LocalRetainedValuesStore`; without a retaining owner, this behaves like [rememberViewStore].
 * The ViewStore and its state collection are recreated when the composition is recreated.
 * This keeps the Store in memory only; it does not survive process death.
 *
 * This function owns the Store and always closes it when retention ends (or creation is
 * abandoned), rather than on transient composition disposal. For an externally owned Store,
 * use the [rememberViewStore] overload that accepts a Store instance.
 *
 * @param keys Keys identifying the retained Store; changing any key creates a new Store.
 * Keys must not reference short-lived UI objects.
 * @param store Factory for a new Store; must not capture short-lived UI objects in the Store
 */
@Composable
fun <S : State, A : Action, E : Event> retainViewStore(vararg keys: Any?, store: () -> Store<S, A, E>): ViewStore<S, A, E> {
    // Always close the Store in onRetired; callers cannot opt out of this cleanup.
    val retainedStore = retain(*keys) { RetainedStore(store()) }
    // Do not close the Store on composition disposal.
    return rememberViewStoreInternal(retainedStore.store, autoClose = false)
}

private class RetainedStore<S : State, A : Action, E : Event>(
    val store: Store<S, A, E>,
) : RetainObserver {
    override fun onRetained() = Unit

    override fun onEnteredComposition() = Unit

    override fun onExitedComposition() = Unit

    override fun onRetired() {
        store.close()
    }

    override fun onUnused() {
        store.close()
    }
}

@Composable
private fun <S : State, A : Action, E : Event> rememberViewStoreInternal(store: Store<S, A, E>, autoClose: Boolean): ViewStore<S, A, E> {
    // Keep the close policy fixed for the lifetime of the remembered Store instance.
    val closeStoreOnDispose = remember(store) { autoClose }

    val state = key(store) {
        store.state.collectAsState()
    }

    DisposableEffect(store) {
        onDispose {
            if (closeStoreOnDispose) {
                store.close()
            }
        }
    }

    // Return the same ViewStore instance across state changes as long as the Store is unchanged.
    return remember(store) {
        ViewStore(
            stateRef = state,
            dispatch = store::dispatch,
            eventFlow = store.event,
        )
    }
}
