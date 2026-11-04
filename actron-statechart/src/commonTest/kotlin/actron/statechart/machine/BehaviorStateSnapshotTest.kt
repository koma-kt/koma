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
                input = MachineInput.EntryResult(activation = activation, output = EntryOutput.Transition(context = SearchState(phase = Phase.Loading, query = "old", revision = 99)), now = MachineTime.Zero),
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
