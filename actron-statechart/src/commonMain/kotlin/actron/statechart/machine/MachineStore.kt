@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.ActionHandlerMatch
import actron.core.Event
import actron.core.InternalActronApi
import actron.core.Plugin
import actron.core.PluginPatch
import actron.core.PluginScope
import actron.core.StateSaver
import actron.core.Store
import actron.core.StoreHandlerMetadata
import actron.core.StoreInternalApi
import actron.core.StorePatch
import actron.core.StorePendingWork
import actron.core.StoreProbe
import actron.core.StoreSetting
import actron.core.StoreTrace
import actron.core.createRuntimeStore
import actron.core.currentInputId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * A [Machine] running as a Actron Store: the state is the [MachineSnapshot], actions are decided
 * by the machine, and the decisions' commands, timers and events are carried out after each
 * commit. See [MachineStore] (the factory) for the protocol.
 *
 * All producers enter one ordered input queue per machine. Only machine decisions write
 * snapshots; handlers return actions, never state updates. Acceptance establishes order for
 * concurrent producers. Closing can discard pending inputs. Keep context deeply immutable.
 * Processing is sequential even on a dispatcher pool; physical thread confinement requires
 * Main or a dedicated single-thread dispatcher. UI collection has its own context.
 */
interface MachineStore<C : Any, A : Action, CMD : Any, E : Event> : Store<MachineSnapshot<C>, A, E> {
    /**
     * Offers [action] and says whether it was accepted, according to the [AdmissionPolicy].
     * [dispatch] is this with the answer dropped. A closed store returns [Admission.Closed];
     * an accepted input may still be discarded by a later close before processing.
     */
    fun admit(action: A): Admission

    /**
     * Offers [action] as an input of the external source [source], under the [AdmissionPolicy]
     * like a dispatch: a source that is refused decides itself whether to retry or drop.
     * [Admission.Closed] is terminal for this store, rather than backpressure to retry. The
     * input is a [MachineInput.External], so a recording knows where it came from.
     */

    fun feed(
        source: SourceId,
        action: A,
    ): Admission

    /**
     * The retained mailbox of the effects whose [EffectPolicy] keeps them; [Store.event] carries
     * the transient ones. See [EffectMailbox].
     */
    val mailbox: EffectMailbox<E>

    /**
     * Delivers the bridge message [message] carrying [action]: it becomes a
     * [MachineInput.BridgeReceived] input, decided like a dispatch and never refused by the
     * [AdmissionPolicy], as refusing it would lose a message another Store already sent.
     * [MachineGroup] calls this for its routes; a replay checks the ids it produced.
     */
    fun deliver(
        message: MessageId,
        action: A,
    )

    /**
     * The executor's state as data, taken at a message boundary of its scheduler: the snapshot
     * of the last decision it carried out, its clock, the commands running and waiting in their
     * lanes, and the commands it has finished with whose last input the machine has not decided
     * yet. The store may have committed later decisions by the time this returns; they are the
     * steps after the checkpoint. A command the snapshot no longer holds is booked as ended,
     * whatever its job is still doing, so the lanes are what the executor settles to.
     *
     * Every position of a `actron-timetravel` recording has such a checkpoint; this one is the
     * live executor's, for a branch that starts where the run is, or for a recording that
     * begins here.
     *
     * @throws IllegalStateException if the store is closed
     */
    suspend fun checkpoint(): ExecutorCheckpoint<C, CMD>
}

/**
 * Creates a Store that runs [machine] from [context].
 *
 * The protocol for one input, inside the store's lock:
 *
 * 1. The input is accepted with the clock's time; a dispatched action becomes
 *    [MachineInput.Dispatch], the startup [MachineInput.Start].
 * 2. [Machine.decide] computes the [Decision] without I/O.
 * 3. A [DecisionOutcome.Handled] decision commits its snapshot; the plugins' `onState` runs
 *    after the commit, and the store's own plugin hands the decision to the scheduler and queues
 *    its events. A [DecisionOutcome.Ignored] input commits nothing. A [DecisionOutcome.Failed]
 *    input commits nothing, registers nothing and fails the handler, so its cause reaches the
 *    exception handler; commands already running are untouched.
 *
 * Outside the lock, in [scope]: the scheduler starts the commands under their lane policies
 * (the pure [Lanes]) and runs them through [handler], which feeds results, completion and
 * failures back as inputs; timers wait on [clock] and feed [MachineInput.TimerFired]; events are
 * delivered through [Store.event] in decision order. A command sees the committed snapshot in
 * [Store.currentState]. If the store closes between the commit and the scheduler's turn, nothing
 * starts. [MachineStore.checkpoint] is the scheduler's state as data.
 *
 * The machine is the only snapshot writer; [builder] exposes runtime settings without arbitrary
 * state handlers or recovery. A `StateSaver` that restores an already started snapshot cannot restore its
 * commands (they are not in the snapshot), so the store starts over from the initial
 * configuration with the restored context.
 *
 * @param machine The machine to run
 * @param context The initial context
 * @param handler Executes the commands
 * @param scope Runs commands, timers and event delivery; needs a real dispatcher, not
 * [Dispatchers.Unconfined], or handlers would run under the store's lock
 * @param clock The machine's clock
 * @param coroutineContext Where decisions and snapshot commits run, independently of command
 * [scope]. Use Dispatchers.Main for UI-thread processing, or a caller-owned single-thread
 * dispatcher for worker-thread confinement. Default/IO pools still serialize decisions but
 * do not pin them to one physical thread. This does not change UI collectors' context.
 * @param admission How many dispatched actions may wait; see [AdmissionPolicy]
 * @param mailbox What the mailbox keeps of the effects and for whom; see [MailboxConfig]
 * @param observers See the decisions as they are committed, ignored or refused; for example
 * `session.decisionsOf(store)` to journal them
 * @param builder Store configuration: plugins, exception handler, state saver, journal
 * @throws IllegalArgumentException if [scope] uses [Dispatchers.Unconfined]
 */
fun <C : Any, A : Action, CMD : Any, E : Event> MachineStore(
    machine: Machine<C, A, CMD, E>,
    context: C,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    clock: MachineClock = MachineClock.monotonic(),
    coroutineContext: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    admission: AdmissionPolicy = AdmissionPolicy.Unbounded,
    observers: List<DecisionObserver<C, A, CMD, E>> = emptyList(),
    mailbox: MailboxConfig<E> = MailboxConfig(),
    builder: MachineConfiguration<C, A, E>.() -> Unit = {},
): MachineStore<C, A, CMD, E> = MachineStoreImpl(machine, context, handler, scope, clock, coroutineContext, admission, observers, mailbox, builder)

@OptIn(InternalActronApi::class)
internal class MachineStoreImpl<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    context: C,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    private val clock: MachineClock,
    coroutineContext: CoroutineContext,
    private val admission: AdmissionPolicy,
    private val observers: List<DecisionObserver<C, A, CMD, E>>,
    mailboxConfig: MailboxConfig<E>,
    builder: MachineConfiguration<C, A, E>.() -> Unit,
    private val origin: StoreOrigin<C, CMD> = StoreOrigin.Fresh,
    recovery: CommandRecoveryStrategy<CMD, A> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
) : MachineStore<C, A, CMD, E>,
    StoreInternalApi<MachineSnapshot<C>, A, E> {
    init {
        require(scope.coroutineContext[ContinuationInterceptor] !== Dispatchers.Unconfined) {
            "[Actron] MachineStore needs an execution scope with a real dispatcher: with Dispatchers.Unconfined, command handlers would run under the store's lock"
        }
    }

    private val initialSnapshot =
        when (origin) {
            StoreOrigin.Fresh -> machine.initialSnapshot(context)
            is StoreOrigin.Resumed -> origin.checkpoint.snapshot
        }

    private val lifetime = StoreLifetime(scope.coroutineContext)
    private val executionScope = lifetime.scope

    // The effects of committed decisions: transient ones to the event flow, the others retained.
    private val mailboxImpl = MailboxImpl(mailboxConfig, executionScope, ::report)

    private val gate = MachineStoreGate()
    private val work = MachineStoreWork<A>(admission)
    private val queue = MachineStoreQueue(gate, work, ::handOff)

    private var reportFailure: (Throwable) -> Unit = {}

    private val scheduler =
        CommandScheduler<C, A, CMD, E>(
            executionScope,
            initialSnapshot,
            handler,
            clock,
            mailboxImpl,
            feed = ::enqueue,
            report = ::report,
            recovery = recovery,
            activities = machine.activities,
            onClosed = { queued, running -> observe { it.onClosed(queued, running) } },
        )

    // Configured plugins and test patches share one adapter to the inner store; the
    // executor hands them every effect, since the inner store never sees the effects.
    private val adaptedPlugins = mutableListOf<AdaptedPlugin<C, A, E>>()

    private fun configuredPlugin(plugin: Plugin<MachineSnapshot<C>, A, E>): AdaptedPlugin<C, A, E> = AdaptedPlugin(plugin, this).also { adaptedPlugins += it }

    private val publication = MachineStorePublication(scheduler::apply, ::observe, ::deliverEffect)
    private val decisions = MachineStoreDecisions(machine, observers, ::observe, publication)

    // Told once, after StoreClosed: a group drops only what processing never decided.
    private val closeListeners = mutableListOf<() -> Unit>()
    private var processingEnded = false

    private val closed: Boolean get() = queue.isClosed

    /** Whether [close] was called: nothing enters or is decided any more. */
    internal val isClosed: Boolean get() = closed

    /** Calls [listener] once when the store closes, after nothing can be decided any more; at once when it has closed already. */
    internal fun onClose(listener: () -> Unit) {
        val now =
            gated {
                if (processingEnded) {
                    true
                } else {
                    closeListeners += listener
                    false
                }
            }
        if (now) listener()
    }

    /** The underlying Actron Store; the module's own tests feed it raw machine inputs. */
    internal val inner: Store<MachineSnapshot<C>, MachineInput<A>, E> =
        createRuntimeStore(initialSnapshot, coroutineContext + lifetime.marker) {
            // Pin restoration even when no saver is configured or initialState is patched.
            if (origin is StoreOrigin.Resumed) stateSaver(origin.adaptStateSaver(StateSaver.Noop()))
            // Only these engine handlers decide snapshots. Public configuration supplies runtime
            // options and typed plugins, without another state handler or recovery writer.
            state<MachineSnapshot<C>> {
                enter {
                    if (origin is StoreOrigin.Resumed) {
                        scheduler.resume(origin.checkpoint)
                        return@enter
                    }
                    val input = currentInputId()
                    val foreign = state.definition != machine.id || state.version != machine.version
                    // A restored snapshot of another machine or version cannot be decided by this
                    // one, and a restored, already started snapshot has lost its commands: both
                    // start over with the restored context. The foreign one is reported.
                    val base = if (foreign || state.isStarted) machine.initialSnapshot(state.context) else state
                    decisions.decide(base, MachineInput.Start(clock.now()), input) { nextState { it } }
                    if (foreign) {
                        val restored = state
                        launch {
                            throw IllegalStateException(
                                "[Actron] MachineStore restored a snapshot of ${restored.definition} version ${restored.version} into ${machine.id} version ${machine.version}; started over with the restored context",
                            )
                        }
                    }
                }
                action<MachineInput<A>> {
                    decisions.decide(state, action, currentInputId()) { nextState { it } }
                }
            }
            probe(
                StoreProbe { trace ->
                    track(trace)
                    if (trace === StoreTrace.StoreClosed) {
                        val listeners =
                            gated {
                                processingEnded = true
                                closeListeners.toList().also { closeListeners.clear() }
                            }
                        for (listener in listeners) listener()
                    }
                },
            )
            MachineConfiguration(actron.statechart.StoreConfiguration(this, lifetime.marker, origin::adaptStateSaver), this@MachineStoreImpl::configuredPlugin).builder()
            plugin(Executor())
            validateRecovery { previous, recovered ->
                require(previous == recovered) { "[Actron] MachineStore recover {} may not change the snapshot; dispatch an action the machine decides" }
            }
        }

    // The inner store is Actron's own, so it is also the bridge actron-test speaks.
    @Suppress("UNCHECKED_CAST")
    private val innerApi: StoreInternalApi<MachineSnapshot<C>, MachineInput<A>, E>
        get() = inner as StoreInternalApi<MachineSnapshot<C>, MachineInput<A>, E>

    private val idle = MachineStoreIdleWaiter(innerApi, queue, ::checkpointActive)

    private inline fun observe(call: (DecisionObserver<C, A, CMD, E>) -> Unit) {
        for (observer in observers) {
            try {
                call(observer)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun report(error: Throwable) {
        // Reaches the store's exception handler (and its probes) like any launched failure.
        reportFailure(error)
    }

    private inner class Executor : Plugin<MachineSnapshot<C>, MachineInput<A>, E> {
        override suspend fun onStart(
            scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
            state: MachineSnapshot<C>,
        ) {
            reportFailure = { error -> scope.launch { throw error } }
        }

        override suspend fun onState(
            scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
            prevState: MachineSnapshot<C>,
            state: MachineSnapshot<C>,
        ) {
            publication.publish(scope, state)
        }
    }

    // Patch adapts caller plugins to the inner Store. Their failure boundary stays here,
    // while publication chooses when and in which effect order to invoke this adapter.
    private suspend fun deliverEffect(
        scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
        state: MachineSnapshot<C>,
        event: E,
    ) {
        for (plugin in adaptedPlugins) {
            try {
                plugin.deliver(scope, state, event)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    override val state: StateFlow<MachineSnapshot<C>> get() = inner.state

    override val event: Flow<E> get() = mailboxImpl.transient

    override val mailbox: EffectMailbox<E> get() = mailboxImpl

    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun start() = inner.start()

    override fun dispatch(action: A) {
        admit(action)
    }

    override fun admit(action: A): Admission = admitting(action) { MachineInput.Dispatch(action, clock.now()) }

    override fun feed(
        source: SourceId,
        action: A,
    ): Admission = admitting(action) { MachineInput.External(source, action, clock.now()) }

    private inline fun admitting(
        action: A,
        input: () -> MachineInput<A>,
    ): Admission {
        if (closed) return Admission.Closed
        // An application clock may throw or block: it must not hold the cut gate or reserve space.
        return submit(action, MachineStoreSubmission(input()))
    }

    private fun submit(
        action: A,
        submission: MachineStoreSubmission<A>,
    ): Admission {
        val result = queue.submit(submission)
        // Observer callbacks may dispatch or close. They cannot run under either gate.
        if (result is Admission.Rejected) observe { it.onRejected(action, result) }
        if (result == Admission.Accepted) queue.drain()
        return result
    }

    private fun track(trace: StoreTrace<MachineSnapshot<C>, MachineInput<A>, E>) = work.track(trace)

    // --- StoreInternalApi: what actron-test's extensions speak ---

    override suspend fun startAndAwait() = innerApi.startAndAwait()

    /**
     * Admits [action] like [dispatch] and waits until the machine decided it: the commit and the
     * observers, not the commands the decision started. A rejected action throws, since it never
     * became an input. During a group cut the input waits in the controlled queue; the call then
     * returns after the thaw, once this input finished. A close discards held or queued inputs
     * and releases their waiters; acceptance does not guarantee processing. An already closed
     * store throws before accepting the action.
     */
    override suspend fun dispatchAndAwait(action: A) {
        // A zero-time idle query never waits, but retains the core's guard against awaiting
        // this same store from its handler, hook, observer or transaction.
        innerApi.awaitIdle(Duration.ZERO)
        check(!closed) { "[Actron] dispatchAndAwait: the store is closed" }
        val completion = CompletableDeferred<Unit>()
        when (val result = submit(action, MachineStoreSubmission(MachineInput.Dispatch(action, clock.now()), completion = MachineStoreCompletion.Awaited(completion)))) {
            Admission.Accepted -> completion.await()
            Admission.Closed -> throw IllegalStateException("[Actron] dispatchAndAwait: the store is closed")
            is Admission.Rejected -> throw IllegalStateException("[Actron] dispatchAndAwait: the action was rejected by the admission policy (pending=${result.pending}, limit=${result.limit})")
        }
    }

    /**
     * Idle for a machine: the inner store has no input queued or being processed and the
     * executor has carried out every decision committed so far, so the results it fed are inputs
     * already counted; iterated until nothing new appeared. Commands still running, timers
     * scheduled and effects pending are data ([checkpoint]), not work to wait for. During a cut
     * the held inputs are outside the inner store, so a frozen store is idle once it drained.
     */
    override suspend fun awaitIdle(timeout: Duration): StorePendingWork = idle.await(timeout)

    // An IllegalStateException from the actor is the existing closed-executor barrier.
    private suspend fun checkpointActive(): Boolean {
        try {
            scheduler.checkpoint()
        } catch (e: IllegalStateException) {
            return false
        }
        return true
    }

    override fun dispatchIf(
        action: A,
        isValid: () -> Boolean,
    ): Unit = throw UnsupportedOperationException("[Actron] dispatchIf is not supported on a MachineStore: an admitted action is booked by the admission policy and cannot be discarded by a predicate; a stale input is the machine's decision")

    /**
     * Applies the settings of [patch] to the inner store and appends its plugins, adapted; the
     * plugins of a MachineStore can be appended but not replaced or cleared (its executor is one
     * of them), and it cannot be probed through a patch (its inputs are machine inputs).
     */
    override fun patch(patch: StorePatch<MachineSnapshot<C>, A, E>): Store<MachineSnapshot<C>, A, E> {
        require(patch.probes.isEmpty()) { "[Actron] A MachineStore cannot be patched with probes: journal it with recordTo() and decisionsOf() instead" }
        val adapted =
            patch.pluginPatches.map { pluginPatch ->
                when (pluginPatch) {
                    is PluginPatch.Append -> pluginPatch.plugins.map { AdaptedPlugin(it, this) }
                    is PluginPatch.Replace, is PluginPatch.Clear -> throw IllegalArgumentException("[Actron] A MachineStore's plugins can be appended, not replaced or cleared: its executor is one of them")
                }
            }
        innerApi.patch(
            StorePatch(
                settings =
                    patch.settings.map { setting ->
                        when (setting) {
                            is StoreSetting.ExecutionContext -> StoreSetting.ExecutionContext(setting.context + lifetime.marker)
                            is StoreSetting.Persistence -> StoreSetting.Persistence(origin.adaptStateSaver(setting.saver))
                            else -> setting
                        }
                    },
                pluginPatches = adapted.map { PluginPatch.Append(it) },
            ),
        )
        for (plugins in adapted) adaptedPlugins += plugins
        return this
    }

    /** The inner store's single handler, matched for a dispatch of [action]; the chart's tools diagnose transitions. */
    override fun matchActionHandlers(
        state: MachineSnapshot<C>,
        action: A,
    ): List<ActionHandlerMatch> = innerApi.matchActionHandlers(state, MachineInput.Dispatch(action, clock.now()))

    override fun handlerMetadata(): StoreHandlerMetadata = innerApi.handlerMetadata()

    override fun deliver(
        message: MessageId,
        action: A,
    ) {
        enqueue(MachineInput.BridgeReceived(message, action, clock.now()))
    }

    // For a group's routes, whose actions lost their type on the way.
    internal fun deliverUnchecked(
        message: MessageId,
        action: Action,
    ) {
        @Suppress("UNCHECKED_CAST")
        deliver(message, action as A)
    }

    override suspend fun checkpoint(): ExecutorCheckpoint<C, CMD> = scheduler.checkpoint()

    private fun enqueue(input: MachineInput<A>) = queue.enqueue(input)

    private fun handOff(submission: MachineStoreSubmission<A>) {
        try {
            work.dispatch(submission, inner::dispatch)
        } catch (failure: Throwable) {
            // Keep the fatal boundary at the Store adapter: shutdown resources before
            // failing its handoff, just as a synchronous core dispatch did before the split.
            close()
            work.failed(submission, failure)
            queue.handoffFailed()
            throw failure
        } finally {
            queue.handoffFinished()
        }
    }

    /**
     * Holds every new input in the controlled queue until [thaw]; the inputs already inside the
     * inner store are still processed. For a group cut.
     */
    internal fun freeze() = queue.freeze()

    /** Releases cut inputs ahead of later arrivals, handing them off outside the gate. */
    internal fun thaw() = queue.thaw()

    internal val heldInputs: Int get() = queue.heldInputs
    internal val pendingAdmissions: Int get() = work.pendingAdmissions

    private inline fun <T : Any> gated(block: () -> T): T = gate.locked(block)

    override fun observeState(observer: (MachineSnapshot<C>) -> Unit): AutoCloseable = inner.observeState(observer)

    override fun observeEvent(observer: (E) -> Unit): AutoCloseable {
        val job = executionScope.launch(start = CoroutineStart.UNDISPATCHED) { event.collect { observer(it) } }
        return AutoCloseable { job.cancel() }
    }

    override suspend fun closeAndJoin() {
        lifetime.checkOutside()
        close()
        inner.closeAndJoin()
        lifetime.job.join()
    }

    override fun close() {
        if (!queue.close()) return
        scheduler.close()
        mailboxImpl.close()
        executionScope.cancel()
        // StoreClosed notifies listeners only after the last inner observer has ended.
        inner.close()
    }
}
