package koma.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Marker supertype for DSL scopes exposed from Store handlers.
 */
sealed interface StoreScope

/**
 * Scope available to an `enter {}` handler for the current state.
 */
@KomaStoreDsl
interface EnterScope<S : State, E : Event, S2 : S> : StoreScope {
    /**
     * The current state snapshot when this handler is executing.
     * This value does not change immediately when a next state is registered.
     */
    val state: S2

    /**
     * Registers the next state to apply after the current enter handler finishes
     * by computing it in the given block.
     * This does not update [state] immediately.
     * If called multiple times in the same handler, the last specified state is used.
     * If no next state is registered, the current [state] is kept.
     *
     * @param block A function that computes and returns the new state
     */
    fun nextState(block: () -> S)

    /**
     * @deprecated Use `nextState { ... }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState(block)"),
    )
    fun nextStateBy(block: () -> S) {
        nextState(block)
    }

    /**
     * @deprecated Use `nextState { state }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState { state }"),
    )
    fun nextState(state: S) {
        nextState { state }
    }

    /**
     * Cancels queued actions that have not started executing yet.
     *
     * The handler or transaction currently in progress keeps running.
     */
    fun clearPendingActions()

    /**
     * Emits an event immediately from the current enter handler.
     * Emission is not deferred until after a next state registered in this handler is applied.
     *
     * @param event The event to emit
     */
    suspend fun event(event: E)

    /**
     * Starts a state-scoped coroutine and returns immediately.
     *
     * The coroutine is cancelled automatically when this state exits.
     *
     * @param dispatcher Optional CoroutineDispatcher override for this coroutine.
     * When null, the coroutine inherits the Store's current execution context.
     * @param block The suspending block of code to execute
     */
    fun launch(dispatcher: CoroutineDispatcher? = null, block: suspend EnterLaunchScope<S, E, S2>.() -> Unit)

    /**
     * Scope available within a state-scoped coroutine launched from `enter {}`.
     */
    @KomaStoreDsl
    interface LaunchScope<S : State, E : Event, S2 : S> : StoreScope {
        /**
         * Whether the owning state runtime is still active.
         */
        val isActive: Boolean

        /**
         * Emits an event immediately from the launched coroutine.
         *
         * @param event The event to emit
         */
        suspend fun event(event: E)

        /**
         * Runs a mutually exclusive transaction against the current Store state and suspends until it
         * completes.
         *
         * If the state has already exited by the time the transaction would run, the transaction is
         * skipped.
         *
         * @param dispatcher Optional CoroutineDispatcher override for this operation.
         * When null, the transaction inherits the Store's current execution context.
         * @param block The suspending block of code to execute as a transaction
         */
        suspend fun transaction(dispatcher: CoroutineDispatcher? = null, block: suspend EnterTransactionScope<S, E, S2>.() -> Unit)

        /**
         * Scope available within a transaction started from an `enter {}` launch.
         */
        @KomaStoreDsl
        interface TransactionScope<S : State, E : Event, S2 : S> : StoreScope {
            /**
             * The current state snapshot when this transaction is executing.
             * This value does not change immediately when a next state is registered.
             */
            val state: S2

            /**
             * Registers the next state to apply after the current transaction finishes
             * by computing it in the given block.
             * This does not update [state] immediately.
             * If called multiple times in the same transaction, the last specified state is used.
             * If no next state is registered, the current [state] is kept.
             *
             * @param block A function that computes and returns the new state
             */
            fun nextState(block: () -> S)

            /**
             * @deprecated Use `nextState { ... }`.
             */
            @Deprecated(
                message = "Use nextState { ... }",
                replaceWith = ReplaceWith("nextState(block)"),
            )
            fun nextStateBy(block: () -> S) {
                nextState(block)
            }

            /**
             * @deprecated Use `nextState { state }`.
             */
            @Deprecated(
                message = "Use nextState { ... }",
                replaceWith = ReplaceWith("nextState { state }"),
            )
            fun nextState(state: S) {
                nextState { state }
            }

            /**
             * Cancels queued actions that have not started executing yet.
             *
             * The handler or transaction currently in progress keeps running.
             */
            fun clearPendingActions()

            /**
             * Emits an event immediately from the current transaction.
             * Emission is not deferred until after a next state registered in this transaction is applied.
             *
             * @param event The event to emit
             */
            suspend fun event(event: E)
        }
    }
}

/**
 * Flattened alias for [EnterScope.LaunchScope] to keep public signatures concise in IDE tooltips.
 *
 * `S2` remains because it carries the narrowed state type through to the transaction scope's `state`.
 */
typealias EnterLaunchScope<S, E, S2> = EnterScope.LaunchScope<S, E, S2>

/**
 * Flattened alias for [EnterScope.LaunchScope.TransactionScope] to keep public signatures concise in IDE tooltips.
 */
typealias EnterTransactionScope<S, E, S2> = EnterScope.LaunchScope.TransactionScope<S, E, S2>

/**
 * Scope available to an `exit {}` handler for the current state.
 */
@KomaStoreDsl
interface ExitScope<S : State, E : Event, S2 : S> : StoreScope {
    /**
     * The current state snapshot when this handler is executing.
     */
    val state: S2

    /**
     * Cancels queued actions that have not started executing yet.
     *
     * The handler or transaction currently in progress keeps running.
     */
    fun clearPendingActions()

    /**
     * Emits an event immediately from the current exit handler.
     *
     * @param event The event to emit
     */
    suspend fun event(event: E)
}

/**
 * Scope available to an `action {}` handler for the current state and action.
 */
@KomaStoreDsl
interface ActionScope<S : State, A : Action, E : Event, S2 : S> : StoreScope {
    /**
     * The current state snapshot when this handler is executing.
     * This value does not change immediately when a next state is registered.
     */
    val state: S2

    /**
     * The action currently being processed.
     */
    val action: A

    /**
     * Registers the next state to apply after the current action handler finishes
     * by computing it in the given block.
     * This does not update [state] immediately.
     * If called multiple times in the same handler, the last specified state is used.
     * If no next state is registered, the current [state] is kept.
     *
     * @param block A function that computes and returns the new state
     */
    fun nextState(block: () -> S)

    /**
     * @deprecated Use `nextState { ... }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState(block)"),
    )
    fun nextStateBy(block: () -> S) {
        nextState(block)
    }

    /**
     * @deprecated Use `nextState { state }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState { state }"),
    )
    fun nextState(state: S) {
        nextState { state }
    }

    /**
     * Cancels queued actions that have not started executing yet.
     *
     * The handler or transaction currently in progress keeps running.
     */
    fun clearPendingActions()

    /**
     * Emits an event immediately from the current action handler.
     * Emission is not deferred until after a next state registered in this handler is applied.
     *
     * @param event The event to emit
     */
    suspend fun event(event: E)

    /**
     * Cancels tracked launches in the current state's runtime for the given explicit lane.
     *
     * Only launches started with [LaunchControl.CancelPrevious] or
     * [LaunchControl.DropIfRunning] and the same explicit [LaunchLane] are affected.
     * If no matching launch exists, this is a no-op.
     *
     * @param lane The explicit launch lane identifying the cancellation group
     */
    fun cancelLaunch(lane: LaunchLane)

    /**
     * Starts a state-scoped coroutine from this action handler and returns immediately.
     *
     * The coroutine is cancelled automatically when this state exits.
     *
     * @param dispatcher Optional CoroutineDispatcher override for this coroutine.
     * When null, the coroutine inherits the Store's current execution context.
     * @param control The launch control used for coordination. Tracked controls may use an
     * explicit [LaunchLane] or the default lane for the current action type.
     * @param block The suspending block of code to execute
     */
    fun launch(dispatcher: CoroutineDispatcher? = null, control: LaunchControl = LaunchControl.Untracked, block: suspend ActionLaunchScope<S, A, E, S2>.() -> Unit)

    /**
     * Scope available within a state-scoped coroutine launched from `action {}`.
     */
    @KomaStoreDsl
    interface LaunchScope<S : State, A : Action, E : Event, S2 : S> : StoreScope {
        /**
         * Whether the owning state runtime is still active.
         */
        val isActive: Boolean

        /**
         * The action being processed when the launch was started.
         */
        val action: A

        /**
         * Emits an event immediately from the launched coroutine.
         *
         * @param event The event to emit
         */
        suspend fun event(event: E)

        /**
         * Runs a mutually exclusive transaction against the current Store state and suspends until it
         * completes.
         *
         * If the state has already exited by the time the transaction would run, the transaction is
         * skipped.
         *
         * @param dispatcher Optional CoroutineDispatcher override for this operation.
         * When null, the transaction inherits the Store's current execution context.
         * @param block The suspending block of code to execute as a transaction
         */
        suspend fun transaction(dispatcher: CoroutineDispatcher? = null, block: suspend ActionTransactionScope<S, A, E, S2>.() -> Unit)

        /**
         * Scope available within a transaction started from an `action {}` launch.
         */
        @KomaStoreDsl
        interface TransactionScope<S : State, A : Action, E : Event, S2 : S> : StoreScope {
            /**
             * The current state snapshot when this transaction is executing.
             * This value does not change immediately when a next state is registered.
             */
            val state: S2

            /**
             * The action that started the launch owning this transaction.
             */
            val action: A

            /**
             * Registers the next state to apply after the current transaction finishes
             * by computing it in the given block.
             * This does not update [state] immediately.
             * If called multiple times in the same transaction, the last specified state is used.
             * If no next state is registered, the current [state] is kept.
             *
             * @param block A function that computes and returns the new state
             */
            fun nextState(block: () -> S)

            /**
             * @deprecated Use `nextState { ... }`.
             */
            @Deprecated(
                message = "Use nextState { ... }",
                replaceWith = ReplaceWith("nextState(block)"),
            )
            fun nextStateBy(block: () -> S) {
                nextState(block)
            }

            /**
             * @deprecated Use `nextState { state }`.
             */
            @Deprecated(
                message = "Use nextState { ... }",
                replaceWith = ReplaceWith("nextState { state }"),
            )
            fun nextState(state: S) {
                nextState { state }
            }

            /**
             * Cancels queued actions that have not started executing yet.
             *
             * The handler or transaction currently in progress keeps running.
             */
            fun clearPendingActions()

            /**
             * Emits an event immediately from the current transaction.
             * Emission is not deferred until after a next state registered in this transaction is applied.
             *
             * @param event The event to emit
             */
            suspend fun event(event: E)
        }
    }
}

/**
 * Flattened alias for [ActionScope.LaunchScope] to keep public signatures concise in IDE tooltips.
 *
 * `S2` remains because it carries the narrowed state type through to the transaction scope's `state`.
 */
typealias ActionLaunchScope<S, A, E, S2> = ActionScope.LaunchScope<S, A, E, S2>

/**
 * Flattened alias for [ActionScope.LaunchScope.TransactionScope] to keep public signatures concise in IDE tooltips.
 */
typealias ActionTransactionScope<S, A, E, S2> = ActionScope.LaunchScope.TransactionScope<S, A, E, S2>

/**
 * Scope available to a `recover {}` handler after a non-fatal exception is caught.
 *
 * Use this to recover from exceptions or update state accordingly.
 */
@KomaStoreDsl
interface RecoverScope<S : State, E : Event, S2 : S, T : Exception> : StoreScope {
    /**
     * The current state snapshot when this handler is executing.
     * This value does not change immediately when a next state is registered.
     */
    val state: S2

    /**
     * The exception currently being handled.
     */
    val error: T

    /**
     * Registers the next state to apply after the current recover handler finishes
     * by computing it in the given block.
     * This does not update [state] immediately.
     * If called multiple times in the same handler, the last specified state is used.
     * If no next state is registered, the current [state] is kept.
     *
     * @param block A function that computes and returns the new state
     */
    fun nextState(block: () -> S)

    /**
     * @deprecated Use `nextState { ... }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState(block)"),
    )
    fun nextStateBy(block: () -> S) {
        nextState(block)
    }

    /**
     * @deprecated Use `nextState { state }`.
     */
    @Deprecated(
        message = "Use nextState { ... }",
        replaceWith = ReplaceWith("nextState { state }"),
    )
    fun nextState(state: S) {
        nextState { state }
    }

    /**
     * Cancels queued actions that have not started executing yet.
     *
     * The handler or transaction currently in progress keeps running.
     */
    fun clearPendingActions()

    /**
     * Emits an event immediately from the current recover handler.
     * Emission is not deferred until after a next state registered in this handler is applied.
     *
     * @param event The event to emit
     */
    suspend fun event(event: E)
}
