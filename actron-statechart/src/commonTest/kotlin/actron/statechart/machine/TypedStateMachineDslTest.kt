package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TypedStateMachineDslTest {
    private sealed interface Phase {
        data object Empty : Phase

        data class Loading(
            val query: String,
        ) : Phase

        data class Results(
            val value: String,
        ) : Phase
    }

    private sealed interface Input : Action {
        data class Search(
            val query: String,
        ) : Input

        data class Loaded(
            val value: String,
        ) : Input

        data object InvalidInternal : Input
    }

    private sealed interface Output : Event

    private fun machine() =
        typedStateMachine<Phase, Input, String, Output>(
            DefinitionId("typed-phases"),
            DefinitionVersion("1"),
            Phase.Empty,
        ) {
            state<Phase.Empty> {
                on<Input.Search> { target { action -> Phase.Loading(action.query) } }
                internal<Input.InvalidInternal> { _, _ -> Phase.Loading("invalid") }
            }
            state<Phase.Loading> {
                onEnter { command("Loading:${(state as Phase.Loading).query}") }
                on<Input.Loaded, Phase.Results> { _, action -> Phase.Results(action.value) }
                after(delay = 2.seconds) { target { Phase.Empty } }
            }
            state<Phase.Results>()
        }

    @Test
    fun graphContextAndReplayStayTogether_forActionsAndTimers() {
        val original = machine()
        val rebuilt = machine()
        assertEquals(listOf("Empty", "Loading", "Results"), original.chart.states.map { it.id.value })
        val start = original.decide(original.initialSnapshot(Phase.Empty), MachineInput.Start(MachineTime.Zero)).snapshot
        val input = MachineInput.Dispatch(Input.Search("Paris"), MachineTime.Zero)
        val entered = original.decide(start, input)
        assertEquals("Loading:Paris", entered.commands.single().command)
        val loaded = entered.snapshot
        assertEquals(Phase.Loading("Paris"), loaded.context)
        assertTrue(loaded.isActive(StateId("Loading")))
        assertEquals(loaded, rebuilt.decide(start, input).snapshot)
        val result = original.decide(loaded, MachineInput.Dispatch(Input.Loaded("Paris result"), MachineTime.Zero)).snapshot
        assertEquals(Phase.Results("Paris result"), result.context)
        assertTrue(result.isActive(StateId("Results")))
        val timedOut = original.decide(loaded, MachineInput.TimerFired(loaded.timers.keys.single(), MachineTime(2.seconds))).snapshot
        assertEquals(Phase.Empty, timedOut.context)
        assertTrue(timedOut.isActive(StateId("Empty")))
        for (snapshot in listOf(start, loaded, result, timedOut)) assertEquals(emptyList(), original.checkInvariants(snapshot))
    }

    @Test
    fun mismatchedStateIsRejectedWithoutCommittingOrExecutingCommands() {
        val machine = machine()
        val start = machine.decide(machine.initialSnapshot(Phase.Empty), MachineInput.Start(MachineTime.Zero)).snapshot
        val action: Input = Input.InvalidInternal
        val decision = machine.decide(start, MachineInput.Dispatch(action, MachineTime.Zero))
        assertIs<DecisionOutcome.Failed>(decision.outcome)
        assertEquals(start, decision.snapshot)
        assertTrue(decision.snapshot.commands.isEmpty())
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun duplicateStateDeclarationsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Phase, Input, String, Output>(
                DefinitionId("duplicate"),
                DefinitionVersion("1"),
                Phase.Empty,
            ) {
                state<Phase.Empty>()
                state<Phase.Empty>()
            }
        }
    }

    @Test
    fun missingTargetIsRejectedAtDefinitionTime() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Phase, Input, String, Output>(
                DefinitionId("invalid"),
                DefinitionVersion("1"),
                Phase.Empty,
            ) {
                state<Phase.Empty> { on<Input.Search, Phase.Loading> { _, _ -> Phase.Loading("") } }
            }
        }
    }
}
