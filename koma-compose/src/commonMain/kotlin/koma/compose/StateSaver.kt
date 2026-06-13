package koma.compose

import androidx.compose.runtime.Composable
import io.github.takahirom.rin.rememberRetained
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
 * @return A [StateSaver] for preserving state snapshots in Compose
 */
@Suppress("unused")
@Composable
fun <S : State> rememberStateSaver(): StateSaver<S> {
    return rememberRetained {
        StateSaverImpl()
    }
}
