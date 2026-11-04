package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.Store
import actron.observability.FailureDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * One ordered mailbox and one atomic snapshot for a parent and its state-scoped child.
 * Start explicitly or dispatch; collecting state/events alone does not start work. Inputs get
 * their logical time when decided, on the processing dispatcher. Callback collectors run in
 * the execution scope; UI integrations should collect state in their own UI context.
 */
interface InvokedMachineStore<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> : Store<InvocationSnapshot<PC, CC>, PA, PE> {
    override val state: StateFlow<InvocationSnapshot<PC, CC>>
    override val currentState: InvocationSnapshot<PC, CC> get() = state.value

    /** Child events are routed inside the composition. Parent effects are exposed here. */
    override val event: Flow<PE>
    val mailbox: EffectMailbox<PE>

    override fun start()

    fun admit(action: PA): Admission

    override fun dispatch(action: PA)

    /** Explicit owner prevents an action from a disposed child UI reaching a new child. */
    fun dispatchChild(
        owner: ActivationId,
        action: CA,
    ): Admission

    suspend fun dispatchAndAwait(action: PA)

    /** Captured in the same queue as decisions; both sides share one clock instant. */
    suspend fun checkpoint(): InvocationCheckpoint<PC, PCMD, CC, CCMD>

    override fun close()
}

fun <PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> InvokedMachineStore(
    machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    context: PC,
    parentHandler: CommandHandler<PCMD, PA>,
    childHandler: CommandHandler<CCMD, CA>,
    scope: CoroutineScope,
    coroutineContext: CoroutineContext = scope.coroutineContext,
    clock: MachineClock = MachineClock.monotonic(),
    mailbox: MailboxConfig<PE> = MailboxConfig(),
    onError: (Throwable) -> Unit = { error -> scope.launch { throw error } },
): InvokedMachineStore<PC, PA, PCMD, PE, CC, CA, CCMD, CE> =
    InvocationStoreImpl(
        machine,
        InvocationCheckpoint(ExecutorCheckpoint.initial(machine.parent.initialSnapshot(context))),
        parentHandler,
        childHandler,
        scope,
        coroutineContext,
        clock,
        CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
        CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
        mailbox,
        onError,
    )

/**
 * Restores the complete composition without entry hooks. Timers pause during absence; only
 * previously running commands use recovery policies. Queued work starts normally. The result
 * is unstarted. Parent and active-child snapshots are structurally validated before runtime
 * creation. Close the old store before replacing it; never run both against the same IO.
 */
fun <PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> resumeInvokedMachineStore(
    machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    checkpoint: InvocationCheckpoint<PC, PCMD, CC, CCMD>,
    parentHandler: CommandHandler<PCMD, PA>,
    childHandler: CommandHandler<CCMD, CA>,
    scope: CoroutineScope,
    coroutineContext: CoroutineContext = scope.coroutineContext,
    clock: MachineClock = MachineClock.monotonic(),
    parentRecovery: CommandRecoveryStrategy<PCMD, PA> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
    childRecovery: CommandRecoveryStrategy<CCMD, CA> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
    mailbox: MailboxConfig<PE> = MailboxConfig(),
    onError: (Throwable) -> Unit = { error -> scope.launch { throw error } },
): InvokedMachineStore<PC, PA, PCMD, PE, CC, CA, CCMD, CE> {
    fun <C : Any, A : Action, CMD : Any, E : Event> validate(
        definition: Machine<C, A, CMD, E>,
        cp: ExecutorCheckpoint<C, CMD>,
    ) {
        require(cp.snapshot.definition == definition.id && cp.snapshot.version == definition.version) { "Checkpoint belongs to a different machine" }
        val issues = definition.validateSnapshotStructure(cp.snapshot)
        require(issues.isEmpty()) { "Checkpoint snapshot is invalid: ${issues.joinToString()}" }
        require(definition.checkInvariants(cp.snapshot).isEmpty()) { "Checkpoint violates machine invariants" }
        require(cp.effects.isEmpty() && cp.ending.isEmpty()) { "Cannot resume undelivered effects or terminal command outcomes" }
    }
    validate(machine.parent, checkpoint.parent)
    require(checkpoint.snapshot.child.matchesParent(checkpoint.parent.snapshot.activations, machine.node)) { "Invocation child does not match parent activation" }
    when (val child = checkpoint.child) {
        ChildCheckpoint.Dormant -> {
            Unit
        }

        is ChildCheckpoint.Active -> {
            validate(machine.child, child.checkpoint)
            require(child.checkpoint.snapshot.isStarted) { "Active child must be started" }
            require(child.checkpoint.now == checkpoint.parent.now) { "Invocation checkpoints must share a clock instant" }
        }
    }
    return InvocationStoreImpl(machine, checkpoint, parentHandler, childHandler, scope, coroutineContext, clock.rebased(checkpoint.parent.now), parentRecovery, childRecovery, mailbox, onError)
}

private class InvocationStoreImpl<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    private val machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    private val initial: InvocationCheckpoint<PC, PCMD, CC, CCMD>,
    parentHandler: CommandHandler<PCMD, PA>,
    private val childHandler: CommandHandler<CCMD, CA>,
    scope: CoroutineScope,
    coroutineContext: CoroutineContext,
    private val clock: MachineClock,
    parentRecovery: CommandRecoveryStrategy<PCMD, PA>,
    private val childRecovery: CommandRecoveryStrategy<CCMD, CA>,
    mailboxConfig: MailboxConfig<PE>,
    private val onError: (Throwable) -> Unit,
) : InvokedMachineStore<PC, PA, PCMD, PE, CC, CA, CCMD, CE> {
    init {
        require(scope.coroutineContext[ContinuationInterceptor] !== Dispatchers.Unconfined) { "Invocation execution requires a real dispatcher" }
    }

    private val owned = StoreLifetime(scope.coroutineContext)
    private val lifetime = owned.job
    private val execution = owned.scope
    private val messages = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(initial.snapshot)
    override val state: StateFlow<InvocationSnapshot<PC, CC>> = mutableState.asStateFlow()
    private val effects = MailboxImpl(mailboxConfig, execution, ::report)
    override val event: Flow<PE> get() = effects.transient
    override val mailbox: EffectMailbox<PE> get() = effects
    private var started = false
    private val parent = InvocationSide(initial.parent.snapshot, initial.parent.lanes, parentHandler, parentRecovery, execution, clock, ::post, { input -> process(InvocationInput.Parent(input)) }, ::report, machine.parent.activities)
    private var child: RunningChild<CC, CA, CCMD> =
        when (val saved = initial.child) {
            ChildCheckpoint.Dormant -> RunningChild.Dormant
            is ChildCheckpoint.Active -> RunningChild.Active(saved.owner, childSide(saved.owner, saved.checkpoint.snapshot, saved.checkpoint.lanes))
        }
    private val actor =
        CoroutineScope(coroutineContext + lifetime + owned.marker)
            .launch {
                for (task in messages) {
                    try {
                        task()
                    } catch (error: Exception) {
                        if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                        report(error)
                    }
                }
            }.also { job ->
                job.invokeOnCompletion {
                    messages.cancel()
                    effects.close()
                    lifetime.cancel()
                }
            }

    private fun report(error: Throwable) {
        try {
            onError(error)
        } catch (callbackFailure: Throwable) {
            execution.launch { throw callbackFailure }
        }
    }

    private fun post(task: () -> Unit): Admission = if (lifetime.isActive && messages.trySend(task).isSuccess) Admission.Accepted else Admission.Closed

    override fun start() {
        post(::ensureStarted)
    }

    override fun dispatch(action: PA) {
        admit(action)
    }

    override fun admit(action: PA): Admission =
        post {
            ensureStarted()
            process(InvocationInput.Parent(MachineInput.Dispatch(action, clock.now())))
        }

    override fun dispatchChild(
        owner: ActivationId,
        action: CA,
    ): Admission =
        post {
            ensureStarted()
            process(InvocationInput.Child(owner, MachineInput.Dispatch(action, clock.now())))
        }

    override suspend fun dispatchAndAwait(action: PA) {
        request {
            ensureStarted()
            process(InvocationInput.Parent(MachineInput.Dispatch(action, clock.now())))
        }
    }

    private suspend fun <T : Any> request(block: () -> T): T {
        val reply = CompletableDeferred<T>()
        val closing = actor.invokeOnCompletion { reply.completeExceptionally(IllegalStateException("Invocation store is closed")) }
        try {
            if (post {
                    try {
                        reply.complete(block())
                    } catch (error: Exception) {
                        reply.completeExceptionally(error)
                    }
                } == Admission.Closed
            ) {
                error("Invocation store is closed")
            }
            return reply.await()
        } finally {
            closing.dispose()
        }
    }

    override suspend fun checkpoint(): InvocationCheckpoint<PC, PCMD, CC, CCMD> =
        request {
            val now = clock.now()
            val childCheckpoint =
                when (val current = child) {
                    RunningChild.Dormant -> ChildCheckpoint.Dormant
                    is RunningChild.Active -> ChildCheckpoint.Active(current.owner, current.side.checkpoint(now))
                }
            InvocationCheckpoint(parent.checkpoint(now).copy(effects = effects.snapshot()), childCheckpoint)
        }

    override fun observeState(observer: (InvocationSnapshot<PC, CC>) -> Unit): AutoCloseable {
        val job = execution.launch { state.collect { observer(it) } }
        return AutoCloseable { job.cancel() }
    }

    override fun observeEvent(observer: (PE) -> Unit): AutoCloseable {
        val job = execution.launch(start = CoroutineStart.UNDISPATCHED) { event.collect { observer(it) } }
        return AutoCloseable { job.cancel() }
    }

    override suspend fun closeAndJoin() {
        owned.checkOutside()
        close()
        lifetime.join()
    }

    override fun close() {
        messages.cancel()
        lifetime.cancel()
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        if (initial.parent.snapshot.isStarted) {
            parent.resume()
            when (val current = child) {
                RunningChild.Dormant -> Unit
                is RunningChild.Active -> current.side.resume()
            }
        } else {
            process(InvocationInput.Parent(MachineInput.Start(clock.now())))
        }
    }

    private fun childSide(
        owner: ActivationId,
        snapshot: MachineSnapshot<CC>,
        lanes: Lanes<CCMD> = Lanes(),
    ): InvocationSide<CC, CA, CCMD> = InvocationSide(snapshot, lanes, childHandler, childRecovery, execution, clock, ::post, { input -> process(InvocationInput.Child(owner, input)) }, ::report, machine.child.activities)

    private fun process(input: InvocationInput<PA, CA>) {
        val decision = machine.decide(mutableState.value, input)
        when (val outcome = decision.outcome) {
            is DecisionOutcome.Failed -> {
                report(outcome.cause)
                return
            }

            is DecisionOutcome.Ignored -> {
                return
            }

            DecisionOutcome.Handled -> {
                Unit
            }
        }
        // The whole composite decision commits before any command can observe it.
        mutableState.value = decision.snapshot
        effects.enqueue(InputAttribution.Unattributed, decision.parentDecisions.flatMap { it.effects })
        parent.apply(decision.snapshot.parent, decision.parentCommands)
        when (val next = decision.snapshot.child) {
            ChildInvocation.Dormant -> {
                val previous = child
                child = RunningChild.Dormant
                if (previous is RunningChild.Active) previous.side.close()
            }

            is ChildInvocation.Active -> {
                val previous = child
                val side =
                    if (previous is RunningChild.Active && previous.owner == next.owner) {
                        previous.side
                    } else {
                        if (previous is RunningChild.Active) previous.side.close()
                        childSide(next.owner, next.snapshot)
                    }
                child = RunningChild.Active(next.owner, side)
                side.apply(next.snapshot, decision.childCommands.map { it.command })
            }
        }
    }
}

private sealed interface RunningChild<out C : Any, out A : Action, out CMD : Any> {
    data object Dormant : RunningChild<Nothing, Nothing, Nothing>

    class Active<C : Any, A : Action, CMD : Any>(
        val owner: ActivationId,
        val side: InvocationSide<C, A, CMD>,
    ) : RunningChild<C, A, CMD>
}

/** Mutable bookkeeping is confined to the composition actor; workers only post messages. */

private class InvocationSide<C : Any, A : Action, CMD : Any>(
    private var snapshot: MachineSnapshot<C>,
    private var lanes: Lanes<CMD>,
    private val handler: CommandHandler<CMD, A>,
    private val recovery: CommandRecoveryStrategy<CMD, A>,
    scope: CoroutineScope,
    private val clock: MachineClock,
    private val post: (() -> Unit) -> Admission,
    private val decide: (MachineInput<A>) -> Unit,
    private val report: (Throwable) -> Unit,
    activities: Map<actron.statechart.StateId, suspend (EntryExecution<C, A, CMD>) -> Unit>,
) {
    private val execution = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val jobs = mutableMapOf<CommandId, Job>()
    private val timers = mutableMapOf<TimerId, Job>()
    private val ending = mutableMapOf<CommandId, CommandRegistration<CMD>>()
    private var closed = false
    private val entries = EntryJobs(scope = execution, definitions = activities, handler = handler, clock = clock, feed = { input -> post { if (!closed) decide(input) } }, report = { error -> post { if (!closed) report(error) } })

    private fun feed(input: (MachineTime) -> MachineInput<A>) {
        post { if (!closed) decide(input(clock.now())) }
    }

    fun resume() {
        entries.update(snapshot = snapshot)
        for (command in lanes.running.values) launch(command, recovering = true)
        scheduleTimers()
    }

    fun apply(
        next: MachineSnapshot<C>,
        commands: List<CommandRegistration<CMD>>,
    ) {
        snapshot = next
        ending.keys.retainAll(next.commands.keys)
        val active = next.activations.values.toSet()
        change(
            lanes.exited(
                lanes.all.values
                    .map { it.scope }
                    .filter { it !in active },
            ),
        )
        for (id in timers.keys.toList()) if (id !in next.timers) timers.remove(id)?.cancel()
        scheduleTimers()
        for (command in commands) change(lanes.admit(command))
        entries.update(snapshot = snapshot)
    }

    private fun scheduleTimers() {
        for ((id, timer) in snapshot.timers) {
            if (id !in timers) {
                val job =
                    execution.launch(start = CoroutineStart.LAZY) {
                        clock.delayUntil(timer.deadline)
                        feed { now -> MachineInput.TimerFired(id, now) }
                    }
                timers[id] = job
                job.start()
            }
        }
    }

    private fun change(change: LaneChange<CMD>) {
        lanes = change.lanes
        for (id in change.cancelled) jobs[id]?.cancel()
        for (abandoned in change.abandoned) {
            val id = abandoned.registration.id
            jobs.remove(id)?.cancel()
            if (id in snapshot.commands) ending[id] = abandoned.registration
            feed { now -> MachineInput.CommandAbandoned(id, abandoned.reason, now) }
        }
        for (command in change.started) launch(command)
    }

    private fun launch(
        command: CommandRegistration<CMD>,
        recovering: Boolean = false,
    ) {
        val job =
            execution.launch(start = CoroutineStart.LAZY) {
                val envelope = CommandEnvelope(command.id, command.command, command.scope, command.lane)
                val results = ResultSink<A> { action -> feed { now -> MachineInput.CommandResult(command.id, action, now) } }
                try {
                    if (recovering) executeRecovery(recovery, handler, envelope, results) else handler.execute(envelope, results)
                    feed { now -> MachineInput.CommandCompleted(command.id, now) }
                } catch (error: Exception) {
                    if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                    feed { now -> MachineInput.CommandFailed(command.id, FailureDescriptor.of(error), now) }
                    post { if (!closed) report(error) }
                }
            }
        jobs[command.id] = job
        job.invokeOnCompletion { post { if (!closed) finished(command.id) } }
        job.start()
    }

    private fun finished(id: CommandId) {
        jobs.remove(id)
        val command = lanes.running[id] ?: return
        if (id in snapshot.commands) ending[id] = command
        change(lanes.finished(id))
    }

    fun checkpoint(now: MachineTime): ExecutorCheckpoint<C, CMD> {
        var settled = lanes
        for (id in settled.running.keys.toList()) if (id !in snapshot.commands) settled = settled.finished(id).lanes
        return ExecutorCheckpoint(snapshot, now, settled, ending.toMap())
    }

    fun close() {
        closed = true
        execution.cancel()
    }
}
