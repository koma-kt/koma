@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class InvocationStorePersistenceTest {
    @Serializable private sealed interface Parent {
        val seed: Int

        @Serializable data class Idle(
            override val seed: Int = 7,
        ) : Parent

        @Serializable data class Active(
            override val seed: Int,
        ) : Parent
    }

    @Serializable private sealed interface Act : Action {
        @Serializable data object Open : Act

        @Serializable data object Close : Act

        @Serializable data object Tick : Act
    }

    @Serializable private data object Finish : Action

    @Serializable private data object Finished : Event

    private fun machine(
        failEntry: Boolean = false,
        parentEvent: Boolean = false,
        completeImmediately: Boolean = false,
        policy: ConcurrencyPolicy = ConcurrencyPolicy.Sequential,
        onIdle: (MachineTime) -> Unit = {},
    ) = typedInvokedMachine<Parent, Act, String, Finished, Int, Finish, String, Finished>(
        DefinitionId("parent"),
        DefinitionVersion("1"),
        Parent.Idle(),
        child =
            stateMachine(DefinitionId("child"), DefinitionVersion("1"), StateId("Working")) {
                state(StateId("Working")) {
                    onEnter {
                        check(!failEntry) { "broken entry" }
                        context += 1
                        if (policy == ConcurrencyPolicy.Independent) {
                            command("first")
                            command("second")
                        } else {
                            command("first", LaneId("work"), policy)
                            command("second", LaneId("work"), policy)
                        }
                    }
                    onExit { event(Finished) }
                    on<Finish>(StateId("Done"))
                    after(10.seconds, StateId("Done"))
                }
                state(StateId("Done")) {}
            },
    ) { child ->
        state<Parent.Idle>(enter = { Parent.Idle(it.seed) }) {
            onEnter { onIdle(now) }
            on<Act.Open, Parent.Active>()
        }
        state<Parent.Active>(enter = { Parent.Active(it.seed) }) {
            onEnter {
                command("parent")
                if (parentEvent) event(Finished)
            }
            internal<Act.Tick> { c, _ -> Parent.Active(seed = c.seed + 1) }
            on<Act.Open, Parent.Active>()
            on<Act.Close, Parent.Idle>()
            invoke(child, context = { it.context.seed }, toChild = { _, send -> if (completeImmediately) send(Finish) }, toParent = { _, send -> send(Act.Close) })
        }
    }

    @Test
    fun serializedCompositionRestartsPendingWorkPromotesQueueAndCompletesParent() =
        runTest {
            val persistence = invocationPersistence(machine())
            val calls = mutableListOf<String>()
            var activeParents = 0
            var activeChildren = 0
            val release = CompletableDeferred<Unit>()
            val parentHandler =
                CommandHandler<String, Act> { _, _ ->
                    calls += "parent"
                    activeParents++
                    try {
                        awaitCancellation()
                    } finally {
                        activeParents--
                    }
                }
            val childHandler =
                CommandHandler<String, Finish> { command, results ->
                    calls += command.command
                    activeChildren++
                    try {
                        if (command.command == "first") release.await() else results.result(Finish)
                    } finally {
                        activeChildren--
                    }
                }
            val first = InvokedMachineStore(machine(), Parent.Idle(), parentHandler, childHandler, backgroundScope)
            first.dispatch(Act.Open)
            testScheduler.runCurrent()
            assertEquals(1, activeParents)
            assertEquals(1, activeChildren)
            val before = first.currentState
            val saved = persistence.save(first)
            first.close()
            testScheduler.runCurrent()
            assertEquals(0, activeParents)
            assertEquals(0, activeChildren)
            val restored = invocationPersistence(machine()).resume(saved, parentHandler, childHandler, backgroundScope)
            assertEquals(before, restored.currentState)
            assertEquals(2, calls.size, "Resume must not start before start()")
            restored.start()
            restored.start()
            testScheduler.runCurrent()
            assertEquals(before, restored.currentState)
            assertEquals(8, assertIs<ChildInvocation.Active<Int>>(restored.currentState.child).snapshot.context)
            assertEquals(2, calls.count { it == "parent" })
            assertEquals(2, calls.count { it == "first" })
            release.complete(Unit)
            testScheduler.runCurrent()
            assertIs<Parent.Idle>(restored.currentState.parent.context)
            assertEquals(ChildInvocation.Dormant, restored.currentState.child)
            assertEquals(1, calls.count { it == "second" })
            assertEquals(0, activeParents)
            assertEquals(0, activeChildren)
            val finished = persistence.save(restored)
            restored.close()
            val again = persistence.resume(finished, parentHandler, childHandler, backgroundScope)
            again.start()
            testScheduler.runCurrent()
            assertEquals(5, calls.size, "Completed work must not return")
            again.close()
        }

    @Test
    fun resumedTimerKeepsRemainingDelayAndCancelsBothSides() =
        runTest {
            val clock = MachineClock.monotonic(testScheduler.timeSource)
            val parent = CommandHandler<String, Act> { _, _ -> awaitCancellation() }
            val child = CommandHandler<String, Finish> { _, _ -> awaitCancellation() }
            val persistence = invocationPersistence(machine())
            val first = InvokedMachineStore(machine(), Parent.Idle(), parent, child, backgroundScope, clock = clock)
            first.dispatch(Act.Open)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(4000)
            val saved = persistence.save(first)
            first.close()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(60000)
            val next = persistence.resume(saved, parent, child, backgroundScope, clock = clock)
            next.start()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(5999)
            testScheduler.runCurrent()
            assertIs<Parent.Active>(next.currentState.parent.context)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertIs<Parent.Idle>(next.currentState.parent.context)
            assertEquals(ChildCheckpoint.Dormant, next.checkpoint().child)
            assertTrue(
                next
                    .checkpoint()
                    .parent.registrations
                    .isEmpty(),
            )
            next.close()
        }

    @Test
    fun reentryCancelsOldChildAndDiscardsLateResultsAndOldUiActions() =
        runTest {
            var active = 0
            val store =
                InvokedMachineStore(
                    machine(),
                    Parent.Idle(),
                    CommandHandler { _, _ -> awaitCancellation() },
                    CommandHandler { _, results ->
                        active++
                        try {
                            awaitCancellation()
                        } finally {
                            active--
                            withContext(NonCancellable) {
                                delay(100)
                                results.result(Finish)
                            }
                        }
                    },
                    backgroundScope,
                )
            store.dispatch(Act.Open)
            testScheduler.runCurrent()
            val oldOwner = assertIs<ChildInvocation.Active<Int>>(store.currentState.child).owner
            store.dispatch(Act.Open)
            testScheduler.runCurrent()
            val newOwner = assertIs<ChildInvocation.Active<Int>>(store.currentState.child).owner
            assertNotEquals(oldOwner, newOwner)
            store.dispatchChild(oldOwner, Finish)
            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()
            assertIs<Parent.Active>(store.currentState.parent.context)
            assertEquals(newOwner, assertIs<ChildInvocation.Active<Int>>(store.currentState.child).owner)
            assertEquals(1, active)
            store.dispatchChild(newOwner, Finish)
            testScheduler.runCurrent()
            assertIs<Parent.Idle>(store.currentState.parent.context)
            assertEquals(0, active)
            store.close()
            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()
            assertEquals(Admission.Closed, store.admit(Act.Open))
        }

    @Test
    fun recoveryWaitsForDecisionReconcilesWithoutRepeatingAndStartsQueuedWorkNormally() =
        runTest {
            val persistence = invocationPersistence(machine())
            val original = InvokedMachineStore(machine(), Parent.Idle(), CommandHandler { _, _ -> awaitCancellation() }, CommandHandler { _, _ -> awaitCancellation() }, backgroundScope)
            original.dispatch(Act.Open)
            testScheduler.runCurrent()
            val saved = persistence.save(original)
            original.close()
            val choice = CompletableDeferred<CommandRecoveryDecision<String, Finish>>()
            val executions = mutableListOf<String>()
            var reconciledParents = 0
            var selections = 0
            val restored =
                persistence.resume(
                    saved,
                    CommandHandler { _, _ -> error("Original parent must not repeat") },
                    CommandHandler { command, results ->
                        executions += command.command
                        results.result(Finish)
                    },
                    backgroundScope,
                    parentRecovery = CommandRecoveryStrategy { CommandRecoveryDecision.Reconcile(CommandHandler { _, _ -> reconciledParents++ }) },
                    childRecovery =
                        CommandRecoveryStrategy {
                            selections++
                            CommandRecoveryDecision.AwaitDecision { choice.await() }
                        },
                )
            restored.start()
            testScheduler.runCurrent()
            assertEquals(1, selections)
            assertEquals(1, reconciledParents)
            assertTrue(executions.isEmpty())
            val waiting = persistence.restore(persistence.save(restored))
            assertTrue(waiting.parent.registrations.isEmpty())
            assertEquals(2, assertIs<ChildCheckpoint.Active<Int, String>>(waiting.child).checkpoint.registrations.size)
            choice.complete(CommandRecoveryDecision.Reconcile(CommandHandler { _, _ -> executions += "check-status" }))
            testScheduler.runCurrent()
            assertEquals(listOf("check-status", "second"), executions)
            assertEquals(1, selections, "Never-run queued work must not be reconciled")
            assertIs<Parent.Idle>(restored.currentState.parent.context)
            restored.close()
        }

    @Test
    fun waitingRecoveryIsCancelledOnExitAndCanBeSavedAndRestoredAgain() =
        runTest {
            val persistence = invocationPersistence(machine())
            val parent = CommandHandler<String, Act> { _, _ -> awaitCancellation() }
            val handler = CommandHandler<String, Finish> { _, _ -> error("No restart authorized") }
            val original = InvokedMachineStore(machine(), Parent.Idle(), parent, CommandHandler { _, _ -> awaitCancellation() }, backgroundScope)
            original.dispatch(Act.Open)
            testScheduler.runCurrent()
            val saved = persistence.save(original)
            original.close()
            var waiting = 0
            val policy =
                CommandRecoveryStrategy<String, Finish> {
                    CommandRecoveryDecision.AwaitDecision {
                        waiting++
                        try {
                            awaitCancellation()
                        } finally {
                            waiting--
                        }
                    }
                }
            val second = persistence.resume(saved, parent, handler, backgroundScope, childRecovery = policy)
            second.start()
            testScheduler.runCurrent()
            assertEquals(1, waiting)
            val again = persistence.save(second)
            second.close()
            testScheduler.runCurrent()
            assertEquals(0, waiting)
            val third = persistence.resume(again, parent, handler, backgroundScope, childRecovery = policy)
            third.start()
            testScheduler.runCurrent()
            assertEquals(1, waiting)
            third.dispatch(Act.Close)
            testScheduler.runCurrent()
            assertEquals(0, waiting)
            third.close()
        }

    @Test
    fun failedCompositeDecisionCommitsNothingAndStartsNoCommands() =
        runTest {
            val errors = mutableListOf<Throwable>()
            var calls = 0
            val store = InvokedMachineStore(machine(failEntry = true), Parent.Idle(), CommandHandler { _, _ -> calls++ }, CommandHandler { _, _ -> calls++ }, backgroundScope, onError = { errors += it })
            store.start()
            testScheduler.runCurrent()
            val before = store.currentState
            store.dispatchAndAwait(Act.Open)
            testScheduler.runCurrent()
            assertEquals(before, store.currentState)
            assertEquals(0, calls)
            assertEquals("broken entry", errors.single().message)
            assertEquals(ChildCheckpoint.Dormant, store.checkpoint().child)
            store.close()
        }

    @Test
    fun retainedEffectsPreventUnsafeSaveAndClosedCheckpointFailsPromptly() =
        runTest {
            val machine = machine(parentEvent = true)
            val store = InvokedMachineStore(machine, Parent.Idle(), CommandHandler { _, _ -> awaitCancellation() }, CommandHandler { _, _ -> awaitCancellation() }, backgroundScope, mailbox = MailboxConfig(policy = { EffectPolicy.Retained() }))
            store.dispatch(Act.Open)
            testScheduler.runCurrent()
            assertEquals(1, store.mailbox.pending.size)
            assertFailsWith<IllegalArgumentException> { invocationPersistence(machine).save(store) }
            store.close()
            assertFailsWith<IllegalStateException> { store.checkpoint() }
            assertEquals(Admission.Closed, store.admit(Act.Open))
        }

    @Test
    fun allLanePoliciesAreAppliedAndClosingCancelsEveryWorker() =
        runTest {
            for (policy in listOf(ConcurrencyPolicy.Sequential, ConcurrencyPolicy.Latest, ConcurrencyPolicy.DropIfRunning, ConcurrencyPolicy.Parallel(2), ConcurrencyPolicy.Independent)) {
                val calls = mutableListOf<String>()
                var active = 0
                val store =
                    InvokedMachineStore(
                        machine(policy = policy),
                        Parent.Idle(),
                        CommandHandler { _, _ -> awaitCancellation() },
                        CommandHandler { command, _ ->
                            calls += command.command
                            active++
                            try {
                                awaitCancellation()
                            } finally {
                                active--
                            }
                        },
                        backgroundScope,
                    )
                store.dispatch(Act.Open)
                testScheduler.runCurrent()
                val cp = assertIs<ChildCheckpoint.Active<Int, String>>(store.checkpoint().child).checkpoint
                val expected =
                    when (policy) {
                        ConcurrencyPolicy.Latest -> listOf("second")
                        ConcurrencyPolicy.Sequential, ConcurrencyPolicy.DropIfRunning -> listOf("first")
                        else -> listOf("first", "second")
                    }
                assertEquals(expected, calls)
                assertEquals(expected.size, cp.lanes.running.size)
                assertEquals(
                    if (policy == ConcurrencyPolicy.Sequential) 1 else 0,
                    cp.lanes.queued.values
                        .sumOf { it.size },
                )
                assertTrue(cp.ending.isEmpty())
                store.close()
                testScheduler.runCurrent()
                assertEquals(0, active)
            }
        }

    @Test
    fun feedbackSettlesBeforeAnyTransientStateCommandStarts() =
        runTest {
            var calls = 0
            val store = InvokedMachineStore(machine(parentEvent = true, completeImmediately = true), Parent.Idle(), CommandHandler { _, _ -> calls++ }, CommandHandler { _, _ -> calls++ }, backgroundScope)
            store.dispatch(Act.Open)
            testScheduler.runCurrent()
            assertIs<Parent.Idle>(store.currentState.parent.context)
            assertEquals(ChildInvocation.Dormant, store.currentState.child)
            assertEquals(0, calls)
            store.close()
        }

    @Test
    fun recoveryFailureIsReportedAndDoesNotStrandTheLane() =
        runTest {
            val persistence = invocationPersistence(machine())
            val parent = CommandHandler<String, Act> { _, _ -> awaitCancellation() }
            val original = InvokedMachineStore(machine(), Parent.Idle(), parent, CommandHandler { _, _ -> awaitCancellation() }, backgroundScope)
            original.dispatch(Act.Open)
            testScheduler.runCurrent()
            val saved = persistence.save(original)
            original.close()
            val errors = mutableListOf<Throwable>()
            val calls = mutableListOf<String>()
            val restored =
                persistence.resume(
                    saved,
                    parent,
                    CommandHandler { command, results ->
                        calls += command.command
                        results.result(Finish)
                    },
                    backgroundScope,
                    childRecovery = CommandRecoveryStrategy { throw IllegalStateException("cannot reconcile") },
                    onError = { errors += it },
                )
            restored.start()
            testScheduler.runCurrent()
            assertEquals("cannot reconcile", errors.single().message)
            assertEquals(listOf("second"), calls)
            assertIs<Parent.Idle>(restored.currentState.parent.context)
            assertTrue(
                restored
                    .checkpoint()
                    .parent.ending
                    .isEmpty(),
            )
            restored.close()
        }

    @Test
    fun freshCheckpointStartsNormallyAndScopeCancellationClosesExecutor() =
        runTest {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var calls = 0
            val parent =
                CommandHandler<String, Act> { _, _ ->
                    calls++
                    awaitCancellation()
                }
            val child =
                CommandHandler<String, Finish> { _, _ ->
                    calls++
                    awaitCancellation()
                }
            val persistence = invocationPersistence(machine())
            val first = InvokedMachineStore(machine(), Parent.Idle(), parent, child, scope)
            try {
                val saved = persistence.save(first)
                assertFalse(first.currentState.parent.isStarted)
                assertEquals(0, calls)
                first.close()
                val next = persistence.resume(saved, parent, child, scope)
                next.dispatchAndAwait(Act.Open)
                testScheduler.runCurrent()
                assertEquals(2, calls)
                assertEquals(8, assertIs<ChildInvocation.Active<Int>>(next.currentState.child).snapshot.context)
                scope.cancel()
                testScheduler.runCurrent()
                assertEquals(Admission.Closed, next.admit(Act.Close))
                assertFailsWith<IllegalStateException> { next.checkpoint() }
            } finally {
                first.close()
                scope.cancel()
            }
        }

    @Test
    fun queuedActionsAndCommandResultsUseTheSameDecisionClock() =
        runTest {
            var time = MachineTime.Zero
            val clock =
                object : MachineClock {
                    override fun now(): MachineTime = time

                    override suspend fun delayUntil(deadline: MachineTime) {
                        awaitCancellation()
                    }
                }
            val idleTimes = mutableListOf<MachineTime>()
            val sinks = mutableListOf<ResultSink<Finish>>()
            val store =
                InvokedMachineStore(
                    machine(onIdle = { idleTimes += it }),
                    Parent.Idle(),
                    CommandHandler { _, _ -> awaitCancellation() },
                    CommandHandler { _, results ->
                        sinks += results
                        awaitCancellation()
                    },
                    backgroundScope,
                    clock = clock,
                )
            store.dispatch(Act.Open)
            testScheduler.runCurrent()
            time = MachineTime(1.seconds)
            store.dispatch(Act.Tick)
            sinks.single().result(Finish)
            time = MachineTime(5.seconds)
            testScheduler.runCurrent()
            assertEquals(listOf(MachineTime.Zero, MachineTime(5.seconds)), idleTimes)
            assertIs<Parent.Idle>(store.currentState.parent.context)
            store.close()
        }
}
