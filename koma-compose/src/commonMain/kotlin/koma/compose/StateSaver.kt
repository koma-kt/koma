package koma.compose

import androidx.compose.runtime.Composable
import io.github.takahirom.rin.rememberRetained
import koma.core.ExperimentalKomaApi
import koma.core.State
import koma.core.StateSaver

private class StateSaverImpl<S : State> : StateSaver<S> {
    private var savedState: S? = null

    override fun save(state: S) {
        savedState = state
    }

    override fun restore(): S? {
        return savedState
    }
}

/**
 * Remembers an in-memory [StateSaver] that survives recomposition.
 *
 * This saver is retained in memory via `rememberRetained` and is intended for Compose-driven
 * state restoration.
 *
 * Prefer [retainViewStore] to keep the Store itself across transient composition recreation.
 * Remove this saver from the Store configuration when migrating to that approach.
 * Neither approach persists state across process death; use an appropriate [StateSaver]
 * implementation if persistent state restoration is required.
 *
 * @return A [StateSaver] for preserving state snapshots in Compose
 */
@Deprecated(
    message = "Use retainViewStore to retain the Store itself across transient composition recreation instead of remembering an in-memory StateSaver.",
    level = DeprecationLevel.WARNING,
)
@ExperimentalKomaApi
@Suppress("unused")
@Composable
fun <S : State> rememberStateSaver(): StateSaver<S> {
    return rememberRetained {
        StateSaverImpl()
    }
}
