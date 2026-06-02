package io.github.komakt.koma.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.komakt.koma.core.Action
import io.github.komakt.koma.core.Event
import io.github.komakt.koma.core.State
import io.github.komakt.koma.core.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
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
@Suppress("unused")
@Stable
class ViewStore<S : State, A : Action, E : Event> internal constructor(
    private val stateRef: ComposeState<S>,
    val dispatch: (action: A) -> Unit = {},
    @PublishedApi internal val eventFlow: Flow<E> = emptyFlow(),
) {
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
            eventFlow.filter { it is E2 }.collect {
                currentBlock.value(currentViewStore.value, it as E2)
            }
        }
    }

    @Deprecated(
        message = "Use eventEffect instead.",
        replaceWith = ReplaceWith("eventEffect<E2>(block)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified E2 : E> handle(noinline block: ViewStore<S, A, E>.(event: E2) -> Unit) = eventEffect<E2>(block)
}

/**
 * Remembers a [Store], collects its state as Compose state, and exposes it through a [ViewStore].
 *
 * Collecting [Store.state] starts the Store immediately.
 * Because [ViewStore.eventEffect] starts collecting later from a [LaunchedEffect], startup events
 * such as events emitted from an initial `enter {}` handler may be emitted before handlers in the
 * same composition begin observing them.
 *
 * The [store] lambda is used only when a new remembered Store must be created for [key].
 * For a given remembered Store instance, this function returns the same [ViewStore] instance across
 * recompositions. A new [ViewStore] is created only when a new Store instance is remembered for
 * [key].
 * The [autoClose] value is fixed when that remembered Store instance is adopted. Recomposition
 * alone does not change close ownership for the same Store, but when [key] causes a different
 * Store instance to be remembered, the current [autoClose] value is used for that new Store.
 *
 * @param key Key used to remember and retain the Store instance
 * @param autoClose Whether to close the Store when the composable leaves the composition
 * @param store Composable function to create the source Store instance
 * @return A ViewStore state holder backed by the remembered Store
 */
@Suppress("unused")
@Composable
fun <S : State, A : Action, E : Event> rememberViewStore(key: Any? = null, autoClose: Boolean = false, store: @Composable () -> Store<S, A, E>): ViewStore<S, A, E> {
    val holder = remember(key) {
        object {
            var value: Store<S, A, E>? = null
        }
    }
    val rememberedStore = holder.value ?: store().also { holder.value = it }
    val closeStoreOnDispose = remember(rememberedStore) { autoClose }

    val state = key(rememberedStore) {
        rememberedStore.state.collectAsState()
    }

    DisposableEffect(rememberedStore) {
        onDispose {
            if (closeStoreOnDispose) {
                rememberedStore.close()
            }
        }
    }

    return remember(rememberedStore) {
        ViewStore(
            stateRef = state,
            dispatch = rememberedStore::dispatch,
            eventFlow = rememberedStore.event,
        )
    }
}
