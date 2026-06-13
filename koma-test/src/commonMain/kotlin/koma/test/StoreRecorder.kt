package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.State
import koma.core.Store

/**
 * Default in-memory recorder for tests. Implemented as a [Plugin] so it integrates with the Store
 * through the same lifecycle hooks as other plugins.
 *
 * Records the state at Store startup (which is the [koma.core.StateSaver]-restored value
 * when present, otherwise the Store's initial state), every committed state transition, and every
 * emitted event, in insertion order.
 */
class StoreRecorder<S : State, A : Action, E : Event> internal constructor() : Plugin<S, A, E> {
    private val recordedStates = mutableListOf<S>()
    private val recordedEvents = mutableListOf<E>()

    /**
     * State snapshots recorded for this Store.
     */
    val states: List<S> = recordedStates

    /**
     * Events recorded for this Store.
     */
    val events: List<E> = recordedEvents

    /**
     * Clears all recorded history.
     */
    fun clear() {
        recordedStates.clear()
        recordedEvents.clear()
    }

    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        recordedStates.add(state)
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        recordedStates.add(state)
    }

    override suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {
        recordedEvents.add(event)
    }
}

/**
 * Creates and registers a [StoreRecorder] for this Store.
 *
 * The recorder is registered as a [Plugin] via [patch], which must happen before the Store is started.
 *
 * @return The registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
fun <S : State, A : Action, E : Event> Store<S, A, E>.createRecorder(): StoreRecorder<S, A, E> {
    val recorder = StoreRecorder<S, A, E>()
    patch { plugin(recorder) }
    return recorder
}

/**
 * Creates a [StoreRecorder], registers it, and runs [block] with this Store as the receiver and
 * the recorder as the argument.
 *
 * Intended for tests that want to scope a recording session to a single block. Inside the block,
 * use [startAndAwait] and [dispatchAndAwait] to drive the Store, then assert against the recorder's
 * `states` and `events`.
 *
 * @param block Test body that receives the registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.record(
    block: suspend Store<S, A, E>.(StoreRecorder<S, A, E>) -> Unit,
) {
    val recorder = createRecorder()
    block(recorder)
}
