package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.InputId
import actron.observability.FailureDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Carries out the intents of committed [Decision]s: runs commands through the [CommandHandler]
 * under their lane policies, fires timers through the [MachineClock], and feeds what comes back
 * as [MachineInput]s.
 *
 * All bookkeeping lives in one actor coroutine, so it needs no lock: [apply] only enqueues a
 * message and returns, which is what a caller holding the store's lock may do. The bookkeeping
 * of commands is the pure [Lanes]; the actor owns only the jobs, the timers and what a
 * [checkpoint] needs. Commands and timers run as children of [parent] in a scope of their own,
 * cancelled by [close].
 */

internal class CommandScheduler<C : Any, A : Action, CMD : Any, E : Event>(
    parent: CoroutineScope,
    initial: MachineSnapshot<C>,
    private val handler: CommandHandler<CMD, A>,
    private val clock: MachineClock,
    private val mailbox: MailboxImpl<E>,
    private val feed: (MachineInput<A>) -> Unit,
    private val report: (Throwable) -> Unit,
    private val recovery: CommandRecoveryStrategy<CMD, A> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
    activities: Map<actron.statechart.StateId, suspend (EntryExecution<C, A, CMD>) -> Unit> = emptyMap(),
    private val onClosed: (queued: List<CommandId>, running: List<CommandId>) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val entries = EntryJobs(scope = scope, definitions = activities, handler = handler, clock = clock, feed = feed, report = report)
    private val messages = Channel<Message>(Channel.UNLIMITED)

    // Actor state: touched only from `actor`.
    private var lanes = Lanes<CMD>()
    private val jobs = mutableMapOf<CommandId, Job>()
    private val timers = mutableMapOf<TimerId, Job>()

    // The snapshot of the last decision carried out, and the commands finished here that it still holds.
    private var lastSnapshot: MachineSnapshot<C> = initial
    private val ending = mutableMapOf<CommandId, CommandRegistration<CMD>>()

    private val actor: Job =
        scope.launch {
            try {
                for (message in messages) handle(message)
            } finally {
                // Closed: the commands never started (queued in a lane, or of a decision the actor did
                // not get to) and those running, cancelled with the scope, are told once.
                val queued =
                    lanes.queued.values
                        .flatten()
                        .map { it.id }
                        .toMutableList()
                while (true) {
                    val message = messages.tryReceive().getOrNull() ?: break
                    if (message is Message.Apply<*, *, *>) queued += message.decision.commands.map { it.id }
                }
                val running = lanes.running.keys.toList()
                if (queued.isNotEmpty() || running.isNotEmpty()) onClosed(queued, running)
            }
        }

    /**
     * Enqueues [decision]'s intents, made while processing [input]; returns at once. Safe to call
     * under the store's lock.
     */
    fun apply(
        decision: Decision<C, CMD, E>,
        input: InputAttribution,
    ) {
        messages.trySend(Message.Apply(decision, input))
    }

    /**
     * The executor's state at its next message boundary; see [MachineStore.checkpoint].
     *
     * @throws IllegalStateException if the scheduler is closed
     */
    suspend fun checkpoint(): ExecutorCheckpoint<C, CMD> {
        val reply = CompletableDeferred<ExecutorCheckpoint<C, CMD>>()
        // A closed actor answers no one: fail the request instead of waiting forever.
        val closing = actor.invokeOnCompletion { reply.completeExceptionally(IllegalStateException("[Actron] The MachineStore is closed; no checkpoint")) }
        try {
            if (messages.trySend(Message.Checkpoint(reply)).isFailure) throw IllegalStateException("[Actron] The MachineStore is closed; no checkpoint")
            return reply.await()
        } finally {
            closing.dispose()
        }
    }

    /**
     * Cancels every command and timer and stops the actor.
     */
    fun close() {
        scope.cancel()
        messages.close()
    }

    fun resume(checkpoint: ExecutorCheckpoint<C, CMD>) {
        messages.trySend(Message.Resume(checkpoint))
    }

    private sealed interface Message {
        class Resume<C : Any, CMD : Any>(
            val checkpoint: ExecutorCheckpoint<C, CMD>,
        ) : Message

        class Apply<C : Any, CMD : Any, E : Event>(
            val decision: Decision<C, CMD, E>,
            val input: InputAttribution,
        ) : Message

        class Finished(
            val command: CommandId,
        ) : Message

        class TimerDone(
            val timer: TimerId,
        ) : Message

        class Checkpoint<C : Any, CMD : Any>(
            val reply: CompletableDeferred<ExecutorCheckpoint<C, CMD>>,
        ) : Message
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.Resume<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val checkpoint = message.checkpoint as ExecutorCheckpoint<C, CMD>
                lastSnapshot = checkpoint.snapshot
                lanes = checkpoint.lanes
                entries.update(snapshot = lastSnapshot)
                for (registration in lanes.running.values) start(registration, recovering = true)
                for ((id, timer) in lastSnapshot.timers) {
                    timers[id] =
                        scope.launch {
                            clock.delayUntil(timer.deadline)
                            feed(MachineInput.TimerFired(id, clock.now()))
                            messages.trySend(Message.TimerDone(id))
                        }
                }
            }

            is Message.Apply<*, *, *> -> {
                @Suppress("UNCHECKED_CAST")
                carryOut(message.decision as Decision<C, CMD, E>, message.input)
            }

            is Message.Finished -> {
                finished(message.command)
            }

            is Message.TimerDone -> {
                timers.remove(message.timer)
            }

            is Message.Checkpoint<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val reply = message.reply as CompletableDeferred<ExecutorCheckpoint<C, CMD>>
                // An inconsistent checkpoint is the requester's failure, not the actor's end.
                try {
                    reply.complete(checkpointNow())
                } catch (e: IllegalArgumentException) {
                    reply.completeExceptionally(e)
                }
            }
        }
    }

    private fun carryOut(
        decision: Decision<C, CMD, E>,
        input: InputAttribution,
    ) {
        lastSnapshot = decision.snapshot
        // Effects first: delivered after the commit, in decision order, before any command runs.
        mailbox.enqueue(input, decision.effects)
        // What the machine deregistered is no longer ending here.
        ending.keys.retainAll(decision.snapshot.commands.keys)
        apply(lanes.exited(decision.cancelledScopes))
        for (timer in decision.timersCancelled) timers.remove(timer)?.cancel()
        for (schedule in decision.timersScheduled) {
            timers[schedule.id] =
                scope.launch {
                    clock.delayUntil(schedule.deadline)
                    feed(MachineInput.TimerFired(schedule.id, clock.now()))
                    messages.trySend(Message.TimerDone(schedule.id))
                }
        }
        for (registration in decision.commands) apply(lanes.admit(registration))
        entries.update(snapshot = decision.snapshot)
    }

    // Carries out a change of the lanes: cancels, reports, starts, in that order.
    private fun apply(change: LaneChange<CMD>) {
        lanes = change.lanes
        for (id in change.cancelled) jobs[id]?.cancel()
        for (abandoned in change.abandoned) {
            val id = abandoned.registration.id
            // Cancelled and told to the machine at once: the job may never have started, so its
            // body cannot be relied on to report anything.
            jobs.remove(id)?.cancel()
            if (id in lastSnapshot.commands) ending[id] = abandoned.registration
            feed(MachineInput.CommandAbandoned(id, abandoned.reason, clock.now()))
        }
        for (registration in change.started) start(registration)
    }

    private fun start(
        registration: CommandRegistration<CMD>,
        recovering: Boolean = false,
    ) {
        val envelope = CommandEnvelope(registration.id, registration.command, registration.scope, registration.lane)
        val job =
            scope.launch {
                try {
                    val results = ResultSink<A> { action -> feed(MachineInput.CommandResult(registration.id, action, clock.now())) }
                    if (recovering) executeRecovery(recovery, handler, envelope, results) else handler.execute(envelope, results)
                    feed(MachineInput.CommandCompleted(registration.id, clock.now()))
                } catch (e: CancellationException) {
                    // The command's own cancellation ends it: its scope exited (the machine already dropped
                    // it), a newer command superseded it (the actor reported it), or the store closed. A
                    // CancellationException thrown while the job is active is a failure, as in core.
                    if (currentCoroutineContext().isActive) fail(registration.id, e) else throw e
                } catch (e: Exception) {
                    fail(registration.id, e)
                }
            }
        jobs[registration.id] = job
        // Runs also for a job cancelled before it started, which its body could not report.
        job.invokeOnCompletion { messages.trySend(Message.Finished(registration.id)) }
    }

    private fun fail(
        command: CommandId,
        error: Exception,
    ) {
        feed(MachineInput.CommandFailed(command, FailureDescriptor.of(error), clock.now()))
        report(error)
    }

    private fun finished(id: CommandId) {
        jobs.remove(id)
        val registration = lanes.running[id] ?: return
        // Its last input (completion, failure) is on its way while the machine still holds it.
        if (id in lastSnapshot.commands) ending[id] = registration
        apply(lanes.finished(id))
    }

    // The lanes as they settle once the ends already decided by the machine are booked: a
    // command the snapshot no longer holds has ended, whatever its job is still doing.
    private fun checkpointNow(): ExecutorCheckpoint<C, CMD> {
        var settled = lanes
        for (id in settled.running.keys.toList()) {
            if (id !in lastSnapshot.commands) settled = settled.finished(id).lanes
        }
        return ExecutorCheckpoint(lastSnapshot, clock.now(), settled, ending.toMap(), mailbox.snapshot())
    }
}
