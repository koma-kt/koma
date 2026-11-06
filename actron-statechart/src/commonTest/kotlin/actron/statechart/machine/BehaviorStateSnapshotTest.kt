@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.statechart.TransitionKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BehaviorStateSnapshotTest {
    private sealed interface SearchState {
        val query: String
        val revision: Int

        data class Idle(
            override val query: String = "",
            override val revision: Int = 0,
        ) : SearchState

        data class Loading(
            override val query: String,
            override val revision: Int,
        ) : SearchState
    }

    private data class Search(
        val query: String,
    ) : Action

    @Test
    fun initialRootValueMustMatchTheDeclaredStartingType() {
        val machine =
            behaviorStateMachine<SearchState, Search, Nothing, Nothing>(
                id = DefinitionId("initial-type"),
                version = DefinitionVersion("1"),
                initialType = SearchState.Idle::class,
            ) {
                state<SearchState.Idle>()
                state<SearchState.Loading>()
            }
        val wrong = machine.initialSnapshot(SearchState.Loading("Paris", 1))
        assertEquals(1, machine.checkInvariants(wrong).size)
        val decision = machine.decide(wrong, MachineInput.Start(MachineTime.Zero))
        assertIs<DecisionOutcome.Failed>(decision.outcome)
        assertEquals(wrong, decision.snapshot)
    }

    @Test
    fun subscriptionKeepsRunningAndAppliesResultsToTheLatestState() =
        runTest {
            val messages = kotlinx.coroutines.channels.Channel<String>(capacity = 2)
            var starts = 0
            var stops = 0
            val machine: Machine<SearchState, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "chat-subscription"),
                    version = DefinitionVersion(value = "1"),
                    initial = SearchState.Idle(),
                ) {
                    state<SearchState.Idle> {
                        onEnter {
                            starts += 1
                            try {
                                for (message in messages) dispatch(Search(message))
                            } finally {
                                stops += 1
                            }
                        }
                        on<Search> { action -> target(state = SearchState.Idle(query = action.query, revision = state.revision + 1), kind = TransitionKind.Internal) }
                    }
                }
            val store =
                MachineStore(
                    machine = machine,
                    context = SearchState.Idle(),
                    handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") },
                    scope = backgroundScope,
                    coroutineContext = StandardTestDispatcher(testScheduler),
                )
            try {
                store.start()
                runCurrent()
                store.dispatch(Search("draft"))
                messages.send("first")
                messages.send("second")
                runCurrent()
                assertEquals(SearchState.Idle(query = "second", revision = 3), store.state.value.context)
                assertEquals(1, starts)
                assertEquals(0, stops)
            } finally {
                store.closeAndJoin()
                messages.close()
            }
            assertEquals(1, stops)
        }

    @Test
    fun updateCannotChangePhaseOrBeCombinedWithAnotherReplacement() {
        for (changePhase in listOf(true, false)) {
            val machine: Machine<SearchState, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "invalid-update"),
                    version = DefinitionVersion(value = "1"),
                    initial = SearchState.Idle(),
                ) {
                    state<SearchState.Idle> {
                        on<Search> {
                            if (changePhase) {
                                target(SearchState.Loading(state.query, state.revision), kind = TransitionKind.Internal)
                            } else {
                                target(SearchState.Idle(query = "partial"), kind = TransitionKind.Internal)
                                target(SearchState.Loading(state.query, state.revision))
                            }
                        }
                    }
                    state<SearchState.Loading>()
                }
            val started = machine.decide(machine.initialSnapshot(SearchState.Idle()), MachineInput.Start(MachineTime.Zero)).snapshot
            val decision = machine.decide(started, MachineInput.Dispatch(Search("ignored"), MachineTime.Zero))
            assertIs<DecisionOutcome.Failed>(decision.outcome)
            assertEquals(started, decision.snapshot)
        }
    }

    @Test
    fun internalUpdatePreservesEntryAndRejectsItsObsoleteReplacement() {
        val machine: Machine<SearchState, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "chat-updates"),
                version = DefinitionVersion(value = "1"),
                initial = SearchState.Idle(),
            ) {
                state<SearchState.Idle> {
                    onEnter { }
                    on<Search> { action -> target(state = SearchState.Idle(query = action.query), kind = TransitionKind.Internal) }
                }
                state<SearchState.Loading>()
            }
        assertEquals(actron.statechart.TransitionStructure.Conservative, machine.chart.transitionStructure)
        val initial = SearchState.Idle()
        val started = machine.decide(machine.initialSnapshot(initial), MachineInput.Start(MachineTime.Zero)).snapshot
        val entry =
            started.pendingEntries.values
                .single()
                .activation
        val updated = machine.decide(started, MachineInput.Dispatch(Search("draft"), MachineTime.Zero))
        assertEquals(started.activations, updated.snapshot.activations)
        assertEquals(started.pendingEntries, updated.snapshot.pendingEntries)
        assertEquals(emptyList(), updated.cancelledScopes)
        val stale =
            machine.decide(
                updated.snapshot,
                MachineInput.EntryResult(
                    entry,
                    EntryOutput.Transition(context = SearchState.Loading(initial.query, initial.revision), expectedContext = initial),
                    MachineTime.Zero,
                ),
            )
        assertIs<DecisionOutcome.Ignored>(stale.outcome)
        assertEquals(updated.snapshot, stale.snapshot)
        val result = machine.decide(updated.snapshot, MachineInput.EntryResult(entry, EntryOutput.Dispatch(Search("message")), MachineTime.Zero))
        assertEquals("message", result.snapshot.context.query)
        assertEquals(started.activations, result.snapshot.activations)
    }

    @Test
    fun localHandlerOverridesCommonHandlerRegardlessOfDeclarationOrder() {
        val machine: Machine<SearchState, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "common-handler"),
                version = DefinitionVersion(value = "1"),
                initial = SearchState.Idle(),
            ) {
                on<Search> { target(state = SearchState.Loading(query = "common", revision = state.revision)) }
                state<SearchState.Idle> {
                    on<Search> { target(state = SearchState.Loading(query = "local", revision = state.revision)) }
                }
                state<SearchState.Loading>()
            }
        val initial = machine.initialSnapshot(context = SearchState.Idle())
        val started = machine.decide(initial, MachineInput.Start(now = MachineTime.Zero)).snapshot
        val local = machine.decide(started, MachineInput.Dispatch(action = Search("first"), now = MachineTime.Zero)).snapshot
        assertEquals("local", local.context.query)
        val common = machine.decide(local, MachineInput.Dispatch(action = Search("second"), now = MachineTime.Zero)).snapshot
        assertEquals("common", common.context.query)
    }

    @Test
    fun actionReadsTheOriginalSnapshotAndAtomicallyReplacesAllItsData() {
        val machine: Machine<SearchState, Search, Nothing, Nothing> =
            behaviorStateMachine(
                id = DefinitionId(value = "readonly-action"),
                version = DefinitionVersion(value = "1"),
                initial = SearchState.Idle(),
            ) {
                state<SearchState.Idle> {
                    onEnter { }
                    on<Search> { action ->
                        val before = state
                        target(state = SearchState.Loading(query = action.query, revision = state.revision + 1))
                        assertSame(expected = before, actual = state)
                    }
                }
                state<SearchState.Loading>()
            }
        val initial = machine.initialSnapshot(context = SearchState.Idle())
        val started = machine.decide(snapshot = initial, input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val activation =
            started.pendingEntries.values
                .single()
                .activation
        val changed = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = Search(query = "London"), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Handled>(value = changed.outcome)
        assertEquals(expected = SearchState.Loading(query = "London", revision = 1), actual = changed.snapshot.context)
        assertEquals(expected = SearchState.Idle(), actual = started.context)
        val stale =
            machine.decide(
                snapshot = changed.snapshot,
                input = MachineInput.EntryResult(activation = activation, output = EntryOutput.Transition(context = SearchState.Loading(query = "old", revision = 99), expectedContext = SearchState.Idle()), now = MachineTime.Zero),
            )
        assertIs<DecisionOutcome.Ignored>(value = stale.outcome)
        assertEquals(expected = changed.snapshot, actual = stale.snapshot)
    }

    @Test
    fun entryReplacesTheWholeSnapshotInsteadOfOnlyItsPhase() =
        runTest {
            val machine: Machine<SearchState, Search, Nothing, Nothing> =
                behaviorStateMachine(
                    id = DefinitionId(value = "readonly-entry"),
                    version = DefinitionVersion(value = "1"),
                    initial = SearchState.Idle(),
                ) {
                    state<SearchState.Idle> {
                        onEnter {
                            val before = state
                            target(state = SearchState.Loading(query = "Paris", revision = state.revision + 1))
                            assertSame(expected = before, actual = state)
                        }
                    }
                    state<SearchState.Loading>()
                }
            val store =
                MachineStore(
                    machine = machine,
                    context = SearchState.Idle(),
                    handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") },
                    scope = backgroundScope,
                    coroutineContext = StandardTestDispatcher(scheduler = testScheduler),
                )
            try {
                store.start()
                runCurrent()
                assertEquals(expected = SearchState.Loading(query = "Paris", revision = 1), actual = store.state.value.context)
            } finally {
                store.closeAndJoin()
            }
        }
}
