package koma.core

/**
 * Persists committed Store state snapshots and optionally restores the last saved snapshot.
 *
 * A restored snapshot replaces the declared initial state before Store startup processing begins.
 */
interface StateSaver<S : State> {
    /**
     * Persists a committed state snapshot.
     *
     * @param state The state to save
     */
    fun save(state: S)

    /**
     * Restores the snapshot to use before Store startup.
     *
     * Return `null` to fall back to the Store's declared initial state.
     *
     * @return The restored state, or null if there is no saved state
     */
    fun restore(): S?

    companion object {
        /**
         * Creates a no-op implementation that never restores and never persists state.
         */
        @Suppress("FunctionName")
        fun <S : State> Noop(): StateSaver<S> = object : StateSaver<S> {
            override fun save(state: S) {}
            override fun restore(): S? {
                return null
            }
        }
    }
}

/**
 * Creates a [StateSaver] from save and restore lambdas.
 */
fun <S : State> StateSaver(save: (state: S) -> Unit, restore: () -> S?) = object : StateSaver<S> {
    override fun save(state: S) {
        save.invoke(state)
    }

    override fun restore(): S? {
        return restore.invoke()
    }
}
