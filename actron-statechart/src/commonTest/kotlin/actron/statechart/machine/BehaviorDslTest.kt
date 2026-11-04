@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BehaviorDslTest {
    private sealed interface Phase {
        data object Idle : Phase

        data class Loading(
            val query: String,
        ) : Phase
    }

    private data class Search(
        val query: String,
    ) : Action

    @Test
    fun nonCancellableOldEntryCannotOverwriteTheNewState() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val machine: Machine<Phase, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "late-entry"),
                    version = DefinitionVersion(value = "1"),
                    initial = Phase.Idle,
                ) {
                    state<Phase.Idle> {
                        onEnter {
                            started.complete(value = Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(context = NonCancellable) {
                                    cleanup.complete(value = Unit)
                                    release.await()
                                    transitionTo(state = Phase.Loading(query = "obsolete"))
                                }
                            }
                        }
                        on<Search> { action -> transitionTo(state = Phase.Loading(query = action.query)) }
                    }
                    state<Phase.Loading>()
                }
            val store = MachineStore(machine = machine, context = Phase.Idle, handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") }, scope = backgroundScope, coroutineContext = StandardTestDispatcher(scheduler = testScheduler))
            store.start()
            started.await()
            store.dispatch(action = Search(query = "current"))
            cleanup.await()
            release.complete(value = Unit)
            runCurrent()
            assertEquals(expected = Phase.Loading(query = "current"), actual = store.state.value.context)
            store.closeAndJoin()
        }

    @Test
    fun twoTransitionsFailWithoutCommittingPartialContext() {
        val machine: Machine<Phase, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "atomic-entry"),
                version = DefinitionVersion(value = "1"),
                initial = Phase.Idle,
            ) {
                state<Phase.Idle> {
                    on<Search> {
                        transitionTo(state = Phase.Loading(query = "first"))
                        transitionTo(state = Phase.Loading(query = "second"))
                    }
                }
                state<Phase.Loading>()
            }
        val before = machine.decide(snapshot = machine.initialSnapshot(context = Phase.Idle), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val after = machine.decide(snapshot = before, input = MachineInput.Dispatch(action = Search(query = "ignored"), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Failed>(value = after.outcome)
        assertEquals(expected = before, actual = after.snapshot)
    }

    @Test
    fun replayDoesNotRunEntryAndLateOutputCannotReplaceANewerActivation() {
        var calls = 0
        val machine: Machine<Phase, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "entry-replay"),
                version = DefinitionVersion(value = "1"),
                initial = Phase.Idle,
            ) {
                state<Phase.Idle> {
                    onEnter { calls += 1 }
                    on<Search> { transitionTo(state = Phase.Idle) }
                }
                state<Phase.Loading>()
            }
        val started = machine.decide(snapshot = machine.initialSnapshot(context = Phase.Idle), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val old =
            started.pendingEntries.values
                .single()
                .activation
        val restarted = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = Search(query = "new"), now = MachineTime.Zero)).snapshot
        val stale = machine.decide(snapshot = restarted, input = MachineInput.EntryResult(activation = old, output = EntryOutput.Transition(context = Phase.Loading(query = "old")), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Ignored>(value = stale.outcome)
        assertEquals(expected = restarted, actual = stale.snapshot)
        assertEquals(expected = 0, actual = calls)
    }

    @Test
    fun resumeRestartsOnlyPendingEntriesAndCloseWaitsForCancellation() =
        runTest {
            var starts = 0
            var cancellations = 0
            val machine: Machine<Phase, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "entry-resume"),
                    version = DefinitionVersion(value = "1"),
                    initial = Phase.Idle,
                ) {
                    state<Phase.Idle> {
                        onEnter {
                            starts += 1
                            try {
                                awaitCancellation()
                            } finally {
                                cancellations += 1
                            }
                        }
                        on<Search> { action -> transitionTo(state = Phase.Loading(query = action.query)) }
                    }
                    state<Phase.Loading> { onEnter { starts += 1 } }
                }
            val handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") }
            val dispatcher = StandardTestDispatcher(scheduler = testScheduler)
            val original = MachineStore(machine = machine, context = Phase.Idle, handler = handler, scope = backgroundScope, coroutineContext = dispatcher)
            original.start()
            runCurrent()
            val pending = original.checkpoint()
            original.closeAndJoin()
            assertEquals(expected = 1, actual = cancellations)
            val restored = resumeMachineStore(machine = machine, checkpoint = pending, handler = handler, scope = backgroundScope, coroutineContext = dispatcher)
            restored.start()
            runCurrent()
            assertEquals(expected = 2, actual = starts)
            restored.dispatch(action = Search(query = "done"))
            runCurrent()
            assertEquals(expected = 3, actual = starts)
            assertEquals(expected = 2, actual = cancellations)
            val completed = restored.checkpoint()
            assertTrue(actual = completed.snapshot.pendingEntries.isEmpty())
            restored.closeAndJoin()
            val finished = resumeMachineStore(machine = machine, checkpoint = completed, handler = handler, scope = backgroundScope, coroutineContext = dispatcher)
            finished.start()
            runCurrent()
            assertEquals(expected = 3, actual = starts)
            finished.closeAndJoin()
        }

    @Test
    fun delayedEntryDoesNotBlockActionsAndExitCancelsIt() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val machine: Machine<Phase, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "async-entry"),
                    version = DefinitionVersion(value = "1"),
                    initial = Phase.Idle,
                ) {
                    state<Phase.Idle> {
                        onEnter {
                            entered.complete(value = Unit)
                            try {
                                delay(duration = 60.seconds)
                                transitionTo(state = Phase.Loading(query = "obsolete"))
                            } finally {
                                cancelled.complete(value = Unit)
                            }
                        }
                        on<Search> { action -> transitionTo(state = Phase.Loading(query = action.query)) }
                    }
                    state<Phase.Loading>()
                }
            val store =
                MachineStore(
                    machine = machine,
                    context = Phase.Idle,
                    handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") },
                    scope = backgroundScope,
                    coroutineContext = StandardTestDispatcher(scheduler = testScheduler),
                )
            store.start()
            entered.await()
            store.dispatch(action = Search(query = "London"))
            cancelled.await()
            assertEquals(expected = Phase.Loading(query = "London"), actual = store.state.value.context)
            assertEquals(expected = 0, actual = testScheduler.currentTime)
            store.closeAndJoin()
        }

    @Test
    fun actionSelectsItsTargetOnceAndUsesTheNormalMachineDecision() {
        var calls = 0
        val machine: Machine<Phase, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "behavior-test"),
                version = DefinitionVersion(value = "1"),
                initial = Phase.Idle,
                build = {
                    state<Phase.Idle>(build = {
                        on<Search>(block = { action ->
                            calls += 1
                            if (action.query.isNotBlank()) {
                                transitionTo(state = Phase.Loading(query = action.query))
                            }
                        })
                    })
                    state<Phase.Loading>()
                },
            )
        val initial = machine.initialSnapshot(context = Phase.Idle)
        val started = machine.decide(snapshot = initial, input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val decision = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = Search(query = "London"), now = MachineTime.Zero))

        assertTrue(actual = decision.isHandled)
        assertEquals(expected = Phase.Loading(query = "London"), actual = decision.snapshot.context)
        assertEquals(expected = 1, actual = calls)
        assertEquals(expected = 1, actual = decision.transitions.size)
        assertEquals(expected = 1, actual = decision.exited.size)
        assertEquals(expected = 1, actual = decision.entered.size)
    }
}
