@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BehaviorStateSnapshotTest {
    private sealed interface Phase {
        data object Idle : Phase

        data object Loading : Phase
    }

    private data class SearchState(
        val phase: Phase = Phase.Idle,
        val query: String = "",
        val revision: Int = 0,
    )

    private data class Search(
        val query: String,
    ) : Action

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
                    initial = Phase.Idle,
                    phaseOf = { state: SearchState -> state.phase },
                ) {
                    state<Phase.Idle> {
                        onEnter {
                            starts += 1
                            try {
                                for (message in messages) dispatch(Search(message))
                            } finally {
                                stops += 1
                            }
                        }
                        on<Search> { action -> updateState(state = state.copy(query = action.query, revision = state.revision + 1)) }
                    }
                }
            val store =
                MachineStore(
                    machine = machine,
                    context = SearchState(),
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
                assertEquals(SearchState(query = "second", revision = 3), store.state.value.context)
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
                    initial = Phase.Idle,
                    phaseOf = { state: SearchState -> state.phase },
                ) {
                    state<Phase.Idle> {
                        on<Search> {
                            if (changePhase) {
                                updateState(state.copy(phase = Phase.Loading))
                            } else {
                                updateState(state.copy(query = "partial"))
                                transitionTo(state.copy(phase = Phase.Loading))
                            }
                        }
                    }
                    state<Phase.Loading>()
                }
            val started = machine.decide(machine.initialSnapshot(SearchState()), MachineInput.Start(MachineTime.Zero)).snapshot
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
                initial = Phase.Idle,
                phaseOf = { state: SearchState -> state.phase },
            ) {
                state<Phase.Idle> {
                    onEnter { }
                    on<Search> { action -> updateState(state = state.copy(query = action.query)) }
                }
                state<Phase.Loading>()
            }
        assertEquals(actron.statechart.TransitionStructure.Conservative, machine.chart.transitionStructure)
        val initial = SearchState()
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
                    EntryOutput.Transition(context = initial.copy(phase = Phase.Loading), expectedContext = initial),
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
                initial = Phase.Idle,
                phaseOf = { state: SearchState -> state.phase },
            ) {
                on<Search> { transitionTo(state = state.copy(phase = Phase.Loading, query = "common")) }
                state<Phase.Idle> {
                    on<Search> { transitionTo(state = state.copy(phase = Phase.Loading, query = "local")) }
                }
                state<Phase.Loading>()
            }
        val initial = machine.initialSnapshot(context = SearchState())
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
                initial = Phase.Idle,
                phaseOf = { state: SearchState -> state.phase },
            ) {
                state<Phase.Idle> {
                    onEnter { }
                    on<Search> { action ->
                        val before = state
                        transitionTo(state = state.copy(phase = Phase.Loading, query = action.query, revision = state.revision + 1))
                        assertSame(expected = before, actual = state)
                    }
                }
                state<Phase.Loading>()
            }
        val initial = machine.initialSnapshot(context = SearchState())
        val started = machine.decide(snapshot = initial, input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val activation =
            started.pendingEntries.values
                .single()
                .activation
        val changed = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = Search(query = "London"), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Handled>(value = changed.outcome)
        assertEquals(expected = SearchState(phase = Phase.Loading, query = "London", revision = 1), actual = changed.snapshot.context)
        assertEquals(expected = SearchState(), actual = started.context)
        val stale =
            machine.decide(
                snapshot = changed.snapshot,
                input = MachineInput.EntryResult(activation = activation, output = EntryOutput.Transition(context = SearchState(phase = Phase.Loading, query = "old", revision = 99), expectedContext = SearchState()), now = MachineTime.Zero),
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
                    initial = Phase.Idle,
                    phaseOf = { state: SearchState -> state.phase },
                ) {
                    state<Phase.Idle> {
                        onEnter {
                            val before = state
                            transitionTo(state = state.copy(phase = Phase.Loading, query = "Paris", revision = state.revision + 1))
                            assertSame(expected = before, actual = state)
                        }
                    }
                    state<Phase.Loading>()
                }
            val store =
                MachineStore(
                    machine = machine,
                    context = SearchState(),
                    handler = CommandHandler<Nothing, Search> { _, _ -> error("No commands") },
                    scope = backgroundScope,
                    coroutineContext = StandardTestDispatcher(scheduler = testScheduler),
                )
            try {
                store.start()
                runCurrent()
                assertEquals(expected = SearchState(phase = Phase.Loading, query = "Paris", revision = 1), actual = store.state.value.context)
            } finally {
                store.closeAndJoin()
            }
        }
}
