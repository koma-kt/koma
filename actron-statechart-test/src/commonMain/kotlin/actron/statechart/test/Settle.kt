package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.PendingEffect
import actron.statechart.machine.TimerId
import actron.test.StoreRecorder
import actron.test.awaitIdle
import actron.test.pendingWork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Waits until the store is idle ([awaitIdle] of actron-test: every input decided, every result the
 * executor fed decided too) and returns the executor's checkpoint there: the commands running and
 * queued, the ones ending, the effects pending, the timers scheduled. What a test asserts on
 * after a `send`: the machine's data, settled.
 *
 * @throws IllegalStateException if the store is not idle after [timeout]
 */
suspend fun <C : Any, A : Action, CMD : Any, E : Event> MachineStore<C, A, CMD, E>.settle(timeout: Duration = 10.seconds): ExecutorCheckpoint<C, CMD> {
    awaitIdle(timeout)
    return checkpoint()
}

/**
 * What a machine still has in hand once its store is idle: work a test either answers, advances
 * the clock for, acknowledges, or asserts absent at the end.
 *
 * @property running The commands the executor runs, awaiting their handler
 * @property queued The commands waiting in their lanes, by lane order
 * @property ending The commands finished whose last input the machine has not decided yet
 * @property timers The timers scheduled, with what is left of each at the checkpoint's clock
 * @property effects The effects waiting in the mailbox or being handled unacknowledged
 */
data class MachinePendingWork<CMD : Any>(
    val running: List<CommandRegistration<CMD>>,
    val queued: List<CommandRegistration<CMD>>,
    val ending: List<CommandId>,
    val timers: Map<TimerId, Duration>,
    val effects: List<PendingEffect<*>>,
    val entries: List<actron.statechart.machine.ActivationId> = emptyList(),
) {
    val isIdle: Boolean get() = listOf(running, queued, ending, timers.keys, effects, entries).all { it.isEmpty() }

    override fun toString(): String =
        buildList {
            if (entries.isNotEmpty()) add("onEnter $entries")
            if (running.isNotEmpty()) add("running ${running.joinToString(",", "[", "]") { "c${it.id.value} ${it.command}" }}")
            if (queued.isNotEmpty()) add("queued ${queued.joinToString(",", "[", "]") { "c${it.id.value} ${it.command}" }}")
            if (ending.isNotEmpty()) add("ending ${ending.joinToString(",", "[", "]") { "c${it.value}" }}")
            if (timers.isNotEmpty()) add("timers ${timers.entries.joinToString(",", "[", "]") { "t${it.key.value} remaining ${it.value}" }}")
            if (effects.isNotEmpty()) add("effects ${effects.joinToString(",", "[", "]") { it.workLabel() }}")
        }.ifEmpty { listOf("nothing") }.joinToString("; ")
}

/** The work this checkpoint holds; see [MachinePendingWork]. */
fun <C : Any, CMD : Any> ExecutorCheckpoint<C, CMD>.pendingWork(): MachinePendingWork<CMD> =
    MachinePendingWork(
        running = lanes.running.values.toList(),
        queued = lanes.queued.values.flatten(),
        ending = ending.keys.toList(),
        timers = snapshot.timers.mapValues { (_, timer) -> timer.deadline - now },
        effects = effects,
        entries = snapshot.pendingEntries.values.map { it.activation },
    )

/**
 * Fails when the store has inputs or launches pending, when the executor holds work (commands
 * running, queued or ending, timers scheduled, effects unacknowledged), or when [recorder]
 * holds events the test did not receive: the end-of-test check of a machine driven step by
 * step. Waits for nothing but the executor's checkpoint; [settle] first when results may be on
 * their way.
 *
 * @throws AssertionError with everything pending
 */
suspend fun <C : Any, A : Action, CMD : Any, E : Event> MachineStore<C, A, CMD, E>.assertNoPendingWork() = assertPending(emptyList())

suspend fun <C : Any, A : Action, CMD : Any, E : Event> MachineStore<C, A, CMD, E>.assertNoPendingWork(recorder: StoreRecorder<MachineSnapshot<C>, A, E>) = assertPending(recorder.unconsumedEvents)

private suspend fun <C : Any, A : Action, CMD : Any, E : Event> MachineStore<C, A, CMD, E>.assertPending(unreceived: List<E>) {
    val store = pendingWork()
    val machine = checkpoint().pendingWork()
    if (store.isIdle && machine.isIdle && unreceived.isEmpty()) return
    throw AssertionError(
        "[Actron] The machine has pending work: ${store.inputs} input(s) pending, ${store.launches} launch(es) running; executor: $machine" +
            (if (unreceived.isNotEmpty()) "; ${unreceived.size} recorded event(s) not received: $unreceived" else ""),
    )
}

private fun PendingEffect<*>.workLabel(): String = "e${id.value} $event${if (handling) " (handling)" else ""}"
