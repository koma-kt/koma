package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationThreadingJvmTest {
    private data class Append(
        val producer: Int,
        val sequence: Int,
    ) : Action

    private sealed interface Output : Event

    private data class Context(
        val values: List<Append> = emptyList(),
    )

    @Test
    fun concurrentInputsAndBothCommandResultsUseOneQueueAndSelectedThread() =
        runBlocking {
            checkNotNull(Executors.newSingleThreadExecutor { runnable: Runnable -> Thread(runnable, "invocation-decisions") }).asCoroutineDispatcher().use { processing ->
                checkNotNull(Executors.newSingleThreadExecutor { runnable: Runnable -> Thread(runnable, "test-ui") }).asCoroutineDispatcher().use { ui ->
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val processingThread = withContext(processing) { checkNotNull(Thread.currentThread()) }
                    val uiThread = withContext(ui) { checkNotNull(Thread.currentThread()) }
                    val threads = ConcurrentLinkedQueue<Thread>()
                    val workerThreads = ConcurrentLinkedQueue<Thread>()
                    val errors = ConcurrentLinkedQueue<Throwable>()
                    val release = CompletableDeferred<Unit>()
                    val ready = List(2) { CompletableDeferred<Unit>() }
                    val child =
                        stateMachine<Context, Append, String, Output>(DefinitionId("thread-child"), DefinitionVersion("1"), StateId("Working")) {
                            state(StateId("Working")) {
                                onEnter { command("child") }
                                internal<Append> { c, a ->
                                    threads.add(checkNotNull(Thread.currentThread()))
                                    c.copy(values = c.values + a)
                                }
                            }
                        }
                    val machine =
                        typedInvokedMachine<Context, Append, String, Output, Context, Append, String, Output>(
                            DefinitionId("thread-parent"),
                            DefinitionVersion("1"),
                            Context(),
                            child = child,
                        ) { invocation ->
                            state<Context> {
                                onEnter { command("parent") }
                                internal<Append> { c, a ->
                                    threads.add(checkNotNull(Thread.currentThread()))
                                    c.copy(values = c.values + a)
                                }
                                invoke(invocation, context = { Context() })
                            }
                        }

                    fun handler(index: Int) =
                        CommandHandler<String, Append> { _, results ->
                            workerThreads.add(checkNotNull(Thread.currentThread()))
                            ready[index].complete(Unit)
                            release.await()
                            results.result(Append(100 + index, 0))
                        }
                    val store = InvokedMachineStore(machine, Context(), handler(0), handler(1), scope, coroutineContext = processing, onError = { errors.add(it) })
                    try {
                        withTimeout(10000) {
                            store.start()
                            ready.forEach { it.await() }
                            val uiRead =
                                async(ui) {
                                    store.state.first {
                                        val childState = it.child
                                        it.parent.context.values.size == 401 && childState is ChildInvocation.Active && childState.snapshot.context.values.size == 1
                                    }
                                    checkNotNull(Thread.currentThread())
                                }
                            (0 until 8).map { producer -> async(Dispatchers.Default) { repeat(50) { assertEquals(Admission.Accepted, store.admit(Append(producer, it))) } } }.awaitAll()
                            release.complete(Unit)
                            assertEquals(uiThread, uiRead.await())
                            val cp = store.checkpoint()
                            for (producer in 0 until 8) {
                                assertEquals(
                                    (0 until 50).toList(),
                                    cp.parent.snapshot.context.values
                                        .filter { it.producer == producer }
                                        .map { it.sequence },
                                )
                            }
                            assertEquals(setOf(processingThread), threads.toSet())
                            assertFalse(workerThreads.contains(processingThread))
                            assertTrue(errors.isEmpty())
                            val readers = (0 until 20).map { async(Dispatchers.Default) { runCatching { store.checkpoint() } } }
                            store.close()
                            readers.awaitAll()
                            assertEquals(Admission.Closed, store.admit(Append(0, 99)))
                            assertFailsWith<IllegalStateException> { store.checkpoint() }
                            Unit
                        }
                    } finally {
                        store.close()
                        scope.cancel()
                    }
                }
            }
        }
}
