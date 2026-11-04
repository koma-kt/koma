package actron.statechart.machine

import actron.core.Action
import actron.statechart.StateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Called only by the executor actor; jobs publish data through its machine input queue. */
internal class EntryJobs<C : Any, A : Action, CMD : Any>(
    private val scope: CoroutineScope,
    private val definitions: Map<StateId, suspend (EntryExecution<C, A, CMD>) -> Unit>,
    private val handler: CommandHandler<CMD, A>,
    private val clock: MachineClock,
    private val feed: (MachineInput<A>) -> Unit,
    private val report: (Throwable) -> Unit,
) {
    private val jobs = mutableMapOf<ActivationId, Job>()

    fun update(snapshot: MachineSnapshot<C>) {
        val active =
            snapshot.pendingEntries.values
                .map { it.activation }
                .toSet()
        for (id in jobs.keys.toList()) {
            if (id !in active) jobs.remove(id)?.cancel()
        }
        for ((node, record) in snapshot.pendingEntries) {
            if (record.activation in jobs) continue
            jobs[record.activation] = start(node = node, record = record, context = snapshot.context)
        }
    }

    private fun start(
        node: StateId,
        record: EntryRecord,
        context: C,
    ): Job =
        scope.launch(context = CoroutineExceptionHandler { _, failure -> report(failure) }) {
            val publish: (EntryOutput<A>) -> Unit = { output ->
                feed(MachineInput.EntryResult(activation = record.activation, output = output, now = clock.now()))
            }
            try {
                definitions.getValue(key = node)(
                    EntryExecution(
                        context = context,
                        now = clock.now(),
                        publish = publish,
                        execute = { command -> execute(record = record, command = command, publish = publish) },
                    ),
                )
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive) report(error) else throw error
            } finally {
                if (currentCoroutineContext().isActive) publish(EntryOutput.Completed)
            }
        }

    private suspend fun execute(
        record: EntryRecord,
        command: CMD,
        publish: (EntryOutput<A>) -> Unit,
    ) {
        val execution = currentCoroutineContext()
        execution.ensureActive()
        handler.execute(
            command = CommandEnvelope(id = record.command, command = command, scope = record.activation, lane = CommandLane.Independent),
            results =
                ResultSink { action ->
                    execution.ensureActive()
                    publish(EntryOutput.Dispatch(action = action))
                },
        )
    }
}
