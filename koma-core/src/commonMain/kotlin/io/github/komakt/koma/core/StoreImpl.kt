package io.github.komakt.koma.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

@OptIn(InternalKomaApi::class)
internal abstract class StoreImpl<S : State, A : Action, E : Event> : Store<S, A, E>, StoreInternalApi<S, A, E> {
    private val _state: MutableStateFlow<S> by lazy {
        isStateRestored = true
        MutableStateFlow(
            try {
                stateSaver.restore() ?: initialState
            } catch (t: Throwable) {
                handleException(t)
                if (t is Exception) {
                    initialState
                } else {
                    throw t
                }
            },
        )
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    final override val state: StateFlow<S> by lazy {
        object : StateFlow<S> {
            override val replayCache: List<S> get() = _state.replayCache
            override val value: S get() = _state.value
            override suspend fun collect(collector: FlowCollector<S>): Nothing = coroutineScope {
                launch {
                    _state.collect(collector)
                }
                if (autoStartPolicy == AutoStartPolicy.OnDispatchOrStateCollection) {
                    launchStartup()
                }
                awaitCancellation()
            }
        }
    }

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    final override val event: Flow<E> = _event

    final override val currentState: S get() = _state.value

    protected abstract var initialState: S

    protected abstract var coroutineContext: CoroutineContext

    protected abstract var stateSaver: StateSaver<S>

    protected abstract var exceptionHandler: ExceptionHandler

    protected abstract var autoStartPolicy: AutoStartPolicy

    protected abstract var pendingActionPolicy: PendingActionPolicy

    protected abstract var pluginExecutionPolicy: PluginExecutionPolicy

    protected abstract val plugins: MutableList<Plugin<S, A, E>>

    protected abstract val onEnter: suspend EnterScope<S, E, S>.() -> Unit

    protected abstract val onAction: suspend ActionScope<S, A, E, S>.() -> Unit

    protected abstract val onExit: suspend ExitScope<S, E, S>.() -> Unit

    protected abstract val onError: suspend RecoverScope<S, E, S, Exception>.() -> Unit

    private val coroutineScope by lazy {
        isCoroutineScopeCreated = true
        CoroutineScope(
            coroutineContext + SupervisorJob(coroutineContext[Job]) + CoroutineExceptionHandler { _, exception ->
                handleException(exception)
            },
        )
    }

    private val dispatchScope by lazy {
        CoroutineScope(
            coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job]),
        )
    }

    private val pluginScope by lazy {
        object : PluginScope<S, A> {
            override fun dispatch(action: A) {
                this@StoreImpl.dispatch(action)
            }

            override fun launch(dispatcher: CoroutineDispatcher?, block: suspend PluginLaunchScope<S, A>.() -> Unit) {
                coroutineScope.launch(dispatcher ?: EmptyCoroutineContext) {
                    block(
                        object : PluginLaunchScope<S, A> {
                            override val currentState: S get() = this@StoreImpl.currentState

                            override fun dispatch(action: A) {
                                this@StoreImpl.dispatch(action)
                            }
                        },
                    )
                }
            }
        }
    }

    private val mutex = Mutex()

    private val stateRuntimes = mutableMapOf<KClass<out S>, StateRuntime>()

    private var activeDispatchJob: Job? = null

    @Volatile
    private var isStateRestored: Boolean = false

    @Volatile
    private var isCoroutineScopeCreated: Boolean = false

    private var isInitialized: Boolean = false

    private data class StateRuntime(
        val scope: CoroutineScope,
        val actionLaunchJobs: MutableMap<Any, Job> = mutableMapOf(),
    )

    final override fun dispatch(action: A) {
        launchDispatch(action)
    }

    final override fun start() {
        launchStartup()
    }

    final override suspend fun startAndAwait() {
        launchStartup().join()
    }

    final override suspend fun dispatchAndAwait(action: A) {
        launchDispatch(action).join()
    }

    final override fun patch(patch: StorePatch<S, A, E>): Store<S, A, E> {
        check(mutex.tryLock()) { "[Koma] Failed to configure the Store because it is starting or already started" }
        try {
            check(!isInitialized) { "[Koma] Store configuration must be applied before the Store is started" }
            if (patch.initialState != null) {
                check(!isStateRestored) { "[Koma] initialState cannot be patched after the state has been read" }
            }
            if (patch.stateSaver != null) {
                check(!isStateRestored) { "[Koma] stateSaver cannot be patched after the state has been read" }
            }
            if (patch.coroutineContext != null) {
                check(!isCoroutineScopeCreated) { "[Koma] coroutineContext cannot be patched after the Store has begun running coroutines" }
            }
            applyConfigurationPatch(patch)
            return this
        } finally {
            mutex.unlock()
        }
    }

    private fun launchStartup(): Job {
        return coroutineScope.launch {
            mutex.withLock {
                initializeIfNeeded()
            }
        }
    }

    private fun launchDispatch(action: A): Job {
        return dispatchScope.launch {
            mutex.withLock {
                val dispatchJob = coroutineContext[Job]
                activeDispatchJob = dispatchJob
                try {
                    initializeIfNeeded()
                    onActionDispatched(currentState, action)
                } finally {
                    if (activeDispatchJob == dispatchJob) {
                        activeDispatchJob = null
                    }
                }
            }
        }
    }

    final override fun collectState(state: (S) -> Unit) {
        coroutineScope.launch {
            this@StoreImpl.state.collect { state(it) }
        }
    }

    final override fun collectEvent(event: (E) -> Unit) {
        coroutineScope.launch {
            this@StoreImpl.event.collect { event(it) }
        }
    }

    final override fun close() {
        coroutineScope.cancel()
    }

    private fun applyConfigurationPatch(patch: StorePatch<S, A, E>) {
        patch.initialState?.let { initialState = it }
        patch.coroutineContext?.let { coroutineContext = it }
        patch.stateSaver?.let { stateSaver = it }
        patch.exceptionHandler?.let { exceptionHandler = it }
        patch.autoStartPolicy?.let { autoStartPolicy = it }
        patch.pendingActionPolicy?.let { pendingActionPolicy = it }
        patch.pluginExecutionPolicy?.let { pluginExecutionPolicy = it }
        patch.pluginPatches.forEach { pluginPatch ->
            when (pluginPatch) {
                is PluginPatch.Append -> plugins.addAll(pluginPatch.plugins)

                is PluginPatch.Replace -> {
                    plugins.clear()
                    plugins.addAll(pluginPatch.plugins)
                }

                is PluginPatch.Clear -> plugins.clear()
            }
        }
    }

    // Must be called only while holding `mutex`.
    private suspend fun initializeIfNeeded() {
        if (isInitialized) return
        processPlugins { onStart(pluginScope, currentState) }
        onStateEntered(currentState)
        isInitialized = true
    }

    private suspend fun emit(event: E) {
        processEventEmit(currentState, event)
    }

    private suspend fun onActionDispatched(state: S, action: A) {
        try {
            val nextState = processActionDispatch(state, action)

            if (state::class != nextState::class) {
                processStateExit(state)
            }

            if (state != nextState) {
                processStateChange(state, nextState)
                if (state::class != nextState::class) {
                    clearPendingActionsOnStateExitIfNeeded()
                }
            }

            if (state::class != nextState::class) {
                onStateEntered(nextState)
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            onErrorOccurred(currentState, t as Exception)
        }
    }

    private suspend fun onStateChanged(state: S, nextState: S) {
        try {
            if (state::class != nextState::class) {
                processStateExit(state)
            }

            if (state != nextState) {
                processStateChange(state, nextState)
                if (state::class != nextState::class) {
                    clearPendingActionsOnStateExitIfNeeded()
                }
            }

            if (state::class != nextState::class) {
                onStateEntered(nextState)
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            onErrorOccurred(currentState, t as Exception)
        }
    }

    private suspend fun onStateEntered(state: S, inErrorHandling: Boolean = false) {
        try {
            val nextState = processStateEnter(state)

            if (state::class != nextState::class) {
                processStateExit(state)
            }

            if (state != nextState) {
                processStateChange(state, nextState)
                if (state::class != nextState::class) {
                    clearPendingActionsOnStateExitIfNeeded()
                }
            }

            if (state::class != nextState::class) {
                onStateEntered(nextState, inErrorHandling = inErrorHandling)
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            if (inErrorHandling) {
                throw InternalError(t)
            }
            onErrorOccurred(currentState, t as Exception)
        }
    }

    private suspend fun onErrorOccurred(state: S, exception: Exception) {
        try {
            val nextState = processError(state, exception)

            if (state::class != nextState::class) {
                processStateExit(state)
            }

            if (state != nextState) {
                processStateChange(state, nextState)
                if (state::class != nextState::class) {
                    clearPendingActionsOnStateExitIfNeeded()
                }
            }

            if (state::class != nextState::class) {
                onStateEntered(nextState, inErrorHandling = true)
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            throw InternalError(t)
        }
    }

    private suspend fun processActionDispatch(state: S, action: A): S {
        processPlugins { onAction(pluginScope, state, action) }
        var newState: S? = null
        onAction.invoke(
            object : ActionScope<S, A, E, S> {
                override val state = state
                override val action = action
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }

                override fun cancelLaunch(lane: LaunchLane) {
                    val stateRuntime = stateRuntimes[state::class] ?: throw InternalError(IllegalStateException("[Koma] State scope is not found"))
                    cancelTrackedActionLaunch(stateRuntime, lane)
                }

                override fun launch(
                    dispatcher: CoroutineDispatcher?,
                    control: LaunchControl,
                    block: suspend ActionLaunchScope<S, A, E, S>.() -> Unit,
                ) {
                    val stateRuntime = stateRuntimes[state::class] ?: throw InternalError(IllegalStateException("[Koma] State scope is not found"))
                    launchActionInStateRuntime(
                        stateRuntime = stateRuntime,
                        action = action,
                        control = control,
                        dispatcher = dispatcher,
                        buildLaunchScope = { buildActionLaunchScope(stateRuntime.scope, action) },
                        block = block,
                    )
                }
            },
        )
        return newState ?: state
    }

    private suspend fun processStateEnter(state: S): S {
        stateRuntimes[state::class]?.scope?.cancel()
        val stateRuntime = StateRuntime(
            scope = CoroutineScope(coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job])),
        )
        stateRuntimes[state::class] = stateRuntime
        var newState: S? = null
        onEnter.invoke(
            object : EnterScope<S, E, S> {
                override val state = state
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }

                override fun launch(dispatcher: CoroutineDispatcher?, block: suspend EnterLaunchScope<S, E, S>.() -> Unit) {
                    launchInStateRuntime(
                        stateRuntime = stateRuntime,
                        dispatcher = dispatcher,
                        buildLaunchScope = { buildEnterLaunchScope(stateRuntime.scope) },
                        block = block,
                    )
                }
            },
        )
        return newState ?: state
    }

    private fun <LS> launchInStateRuntime(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ): Job {
        return stateRuntime.scope.launch(dispatcher ?: EmptyCoroutineContext) {
            executeLaunchInStateRuntime(
                stateRuntime = stateRuntime,
                dispatcher = dispatcher,
                buildLaunchScope = buildLaunchScope,
                block = block,
            )
        }
    }

    private fun <LS> launchActionInStateRuntime(
        stateRuntime: StateRuntime,
        action: A,
        control: LaunchControl,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ) {
        when (control) {
            LaunchControl.Untracked -> {
                launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
            }

            is LaunchControl.CancelPrevious -> {
                val trackedKey = resolveTrackedActionLaunchKey(action = action, control = control)
                cancelTrackedActionLaunch(stateRuntime, trackedKey)
                stateRuntime.actionLaunchJobs[trackedKey] = launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
            }

            is LaunchControl.DropIfRunning -> {
                val trackedKey = resolveTrackedActionLaunchKey(action = action, control = control)
                if (stateRuntime.actionLaunchJobs[trackedKey]?.isActive == true) return
                stateRuntime.actionLaunchJobs[trackedKey] = launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
            }
        }
    }

    private fun resolveTrackedActionLaunchKey(action: A, control: LaunchControl): Any {
        return when (control) {
            LaunchControl.Untracked -> error("Untracked launches do not have a tracked lane")
            is LaunchControl.CancelPrevious -> control.lane ?: action::class
            is LaunchControl.DropIfRunning -> control.lane ?: action::class
        }
    }

    private fun cancelTrackedActionLaunch(stateRuntime: StateRuntime, trackedKey: Any) {
        stateRuntime.actionLaunchJobs.remove(trackedKey)?.cancel()
    }

    private suspend fun <LS> executeLaunchInStateRuntime(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ) {
        val launchScope = buildLaunchScope()
        try {
            block(launchScope)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            coroutineScope.launch(dispatcher ?: EmptyCoroutineContext) {
                mutex.withLock {
                    if (stateRuntime.scope.isActive) {
                        onErrorOccurred(currentState, t as Exception)
                    }
                }
            }
        }
    }

    private fun buildEnterLaunchScope(stateScope: CoroutineScope): EnterLaunchScope<S, E, S> {
        return object : EnterLaunchScope<S, E, S> {
            override val isActive: Boolean get() = stateScope.isActive

            override suspend fun event(event: E) {
                emit(event)
            }

            override suspend fun transaction(dispatcher: CoroutineDispatcher?, block: suspend EnterTransactionScope<S, E, S>.() -> Unit) {
                val job = coroutineScope.launch(dispatcher ?: EmptyCoroutineContext) {
                    mutex.withLock {
                        if (stateScope.isActive) {
                            var newState: S? = null
                            val transactionScope = object : EnterTransactionScope<S, E, S> {
                                override val state: S = currentState

                                override fun nextState(block: () -> S) {
                                    newState = block()
                                }

                                override fun clearPendingActions() {
                                    clearPendingDispatchJobs()
                                }

                                override suspend fun event(event: E) {
                                    emit(event)
                                }
                            }
                            try {
                                block(transactionScope)
                            } catch (t: Throwable) {
                                rethrowIfNonRecoverable(t)
                                onErrorOccurred(currentState, t as Exception)
                                return@withLock
                            }
                            val nextState = newState ?: currentState
                            if (nextState != currentState) {
                                onStateChanged(currentState, nextState)
                            }
                        }
                    }
                }
                job.join()
            }
        }
    }

    private fun buildActionLaunchScope(stateScope: CoroutineScope, launchedAction: A): ActionLaunchScope<S, A, E, S> {
        return object : ActionLaunchScope<S, A, E, S> {
            override val isActive: Boolean get() = stateScope.isActive
            override val action: A = launchedAction

            override suspend fun event(event: E) {
                emit(event)
            }

            override suspend fun transaction(dispatcher: CoroutineDispatcher?, block: suspend ActionTransactionScope<S, A, E, S>.() -> Unit) {
                val job = coroutineScope.launch(dispatcher ?: EmptyCoroutineContext) {
                    mutex.withLock {
                        if (stateScope.isActive) {
                            var newState: S? = null
                            val transactionScope = object : ActionTransactionScope<S, A, E, S> {
                                override val state: S = currentState
                                override val action: A = launchedAction

                                override fun nextState(block: () -> S) {
                                    newState = block()
                                }

                                override fun clearPendingActions() {
                                    clearPendingDispatchJobs()
                                }

                                override suspend fun event(event: E) {
                                    emit(event)
                                }
                            }
                            try {
                                block(transactionScope)
                            } catch (t: Throwable) {
                                rethrowIfNonRecoverable(t)
                                onErrorOccurred(currentState, t as Exception)
                                return@withLock
                            }
                            val nextState = newState ?: currentState
                            if (nextState != currentState) {
                                onStateChanged(currentState, nextState)
                            }
                        }
                    }
                }
                job.join()
            }
        }
    }

    private suspend fun processStateExit(state: S) {
        try {
            onExit.invoke(
                object : ExitScope<S, E, S> {
                    override val state = state

                    override fun clearPendingActions() {
                        clearPendingDispatchJobs()
                    }

                    override suspend fun event(event: E) {
                        emit(event)
                    }
                },
            )
        } finally {
            stateRuntimes[state::class]?.scope?.cancel()
            stateRuntimes.remove(state::class)
        }
    }

    private suspend fun processStateChange(state: S, nextState: S) {
        _state.update { nextState }
        try {
            stateSaver.save(nextState)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            throw InternalError(t)
        }
        processPlugins { onState(pluginScope, state, nextState) }
    }

    private suspend fun processError(state: S, throwable: Exception): S {
        var newState: S? = null
        onError.invoke(
            object : RecoverScope<S, E, S, Exception> {
                override val state = state
                override val error = throwable
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }
            },
        )
        return newState ?: state
    }

    private suspend fun processEventEmit(state: S, event: E) {
        _event.emit(event)
        processPlugins { onEvent(pluginScope, state, event) }
    }

    private fun clearPendingActionsOnStateExitIfNeeded() {
        if (pendingActionPolicy == PendingActionPolicy.ClearOnStateExit && isInitialized) {
            clearPendingDispatchJobs()
        }
    }

    private fun clearPendingDispatchJobs() {
        val currentJob = activeDispatchJob
        val dispatchScopeJob = dispatchScope.coroutineContext[Job] ?: return
        dispatchScopeJob.children
            .filter { it != currentJob && it.isActive }
            .forEach { it.cancel() }
    }

    private suspend fun processPlugins(block: suspend Plugin<S, A, E>.() -> Unit) {
        try {
            when (pluginExecutionPolicy) {
                PluginExecutionPolicy.Concurrent -> coroutineScope {
                    plugins.forEach { plugin ->
                        launch { plugin.block() }
                    }
                }

                PluginExecutionPolicy.InRegistrationOrder -> plugins.forEach { plugin ->
                    plugin.block()
                }
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            throw InternalError(t)
        }
    }

    private fun handleException(t: Throwable) {
        val handled = if (t is InternalError) t.original else t
        exceptionHandler.handle(handled)
    }

    private fun rethrowIfNonRecoverable(t: Throwable) {
        if (t is CancellationException || t !is Exception) {
            throw t
        }
    }

    private class InternalError(val original: Throwable) : Throwable(original)
}
