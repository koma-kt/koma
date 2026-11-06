@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputKind
import actron.core.StoreProbe
import actron.core.StoreTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class MviThreadingJvmTest {
    private data class Append(
        val producer: Int,
        val sequence: Int,
    ) : Action

    private data class Context(
        val values: List<Append> = emptyList(),
    )

    private sealed interface Output : Event

    @Test
    fun concurrentProducersAndParallelResultsReduceSequentiallyOnSelectedThread() =
        runBlocking {
            val executor = checkNotNull(Executors.newSingleThreadExecutor { task: Runnable -> Thread(task, "mvi-reducer") })
            executor.asCoroutineDispatcher().use { processing ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val threads = mutableSetOf<Thread>()
                val processingThread: Thread = withContext(processing) { checkNotNull(Thread.currentThread()) }
                val commandsReady = List(2) { CompletableDeferred<Unit>() }
                val releaseCommands = CompletableDeferred<Unit>()
                val machine =
                    typedStateMachine<Context, Append, Int, Output>(
                        DefinitionId("mvi-threading"),
                        DefinitionVersion("1"),
                        Context(),
                    ) {
                        state<Context> {
                            onEnter {
                                command(0)
                                command(1)
                            }
                            internal<Append> { context, action ->
                                val thread: Thread = Thread.currentThread()
                                threads += thread
                                context.copy(values = context.values + action)
                            }
                        }
                    }
                val handler =
                    commandHandler<Int, Append>(onFailure = { _, error -> throw error }) {
                        commandsReady[it].complete(Unit)
                        releaseCommands.await()
                        Append(10 + it, 0)
                    }
                val accepted = ConcurrentLinkedQueue<Append>()
                val store =
                    MachineStore(machine, Context(), handler, scope, coroutineContext = processing) {
                        probe(
                            StoreProbe { trace ->
                                if (trace is StoreTrace.InputAccepted) {
                                    val kind = trace.kind
                                    if (kind is InputKind.Dispatch) {
                                        when (val input = kind.action) {
                                            is MachineInput.Dispatch -> accepted.add(input.action)
                                            is MachineInput.CommandResult -> accepted.add(input.action)
                                            else -> Unit
                                        }
                                    }
                                }
                            },
                        )
                    }
                try {
                    withTimeout(10_000) {
                        store.start()
                        commandsReady.forEach { it.await() }
                        // Both requests have started before either is allowed to finish.
                        val senders =
                            (0 until 8).map { producer ->
                                async(Dispatchers.Default) {
                                    repeat(50) { store.dispatch(Append(producer, it)) }
                                }
                            }
                        releaseCommands.complete(Unit)
                        senders.awaitAll()
                        val result =
                            store.state
                                .first { it.context.values.size == 402 }
                                .context.values
                        assertEquals(402, result.distinct().size)
                        assertEquals(accepted.toList(), result, "Every input must reduce in acceptance order")
                        for (producer in 0 until 8) {
                            assertEquals((0 until 50).toList(), result.filter { it.producer == producer }.map { it.sequence })
                        }
                        assertEquals(setOf(processingThread), threads)
                    }
                } finally {
                    store.close()
                    scope.cancel()
                }
            }
        }
}
