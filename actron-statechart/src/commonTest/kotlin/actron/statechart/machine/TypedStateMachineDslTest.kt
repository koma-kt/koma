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

        data object Loading : Phase

        data class Results(
            val value: String,
        ) : Phase
    }

    private data class Context(
        val phase: Phase = Phase.Empty,
        val query: String = "",
    )

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

    private fun machine(brokenWriter: Boolean = false) =
        typedStateMachine<Context, Phase, Input, String, Output>(
            DefinitionId("typed-phases"),
            DefinitionVersion("1"),
            Phase.Empty,
            readState = { it.phase },
            writeState = { context, phase -> if (brokenWriter) context else context.copy(phase = phase) },
        ) {
            state<Phase.Empty> {
                on<Input.Search, Phase.Loading>(reduce = { context, action -> context.copy(query = action.query) }) { _, _ -> Phase.Loading }
                internal<Input.InvalidInternal> { context, _ -> context.copy(phase = Phase.Loading) }
            }
            state<Phase.Loading> {
                onEnter { command("${context.phase}:${context.query}") }
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
        val start = original.decide(original.initialSnapshot(Context()), MachineInput.Start(MachineTime.Zero)).snapshot
        val input = MachineInput.Dispatch(Input.Search("Paris"), MachineTime.Zero)
        val entered = original.decide(start, input)
        assertEquals("Loading:Paris", entered.commands.single().command)
        val loaded = entered.snapshot
        assertEquals(Phase.Loading, loaded.context.phase)
        assertTrue(loaded.isActive(StateId("Loading")))
        assertEquals("Paris", loaded.context.query)
        assertEquals(loaded, rebuilt.decide(start, input).snapshot)
        val result = original.decide(loaded, MachineInput.Dispatch(Input.Loaded("Paris result"), MachineTime.Zero)).snapshot
        assertEquals(Phase.Results("Paris result"), result.context.phase)
        assertTrue(result.isActive(StateId("Results")))
        val timedOut = original.decide(loaded, MachineInput.TimerFired(loaded.timers.keys.single(), MachineTime(2.seconds))).snapshot
        assertEquals(Phase.Empty, timedOut.context.phase)
        assertTrue(timedOut.isActive(StateId("Empty")))
        for (snapshot in listOf(start, loaded, result, timedOut)) assertEquals(emptyList(), original.checkInvariants(snapshot))
    }

    @Test
    fun mismatchedStateIsRejectedWithoutCommittingOrExecutingCommands() {
        for (brokenWriter in listOf(false, true)) {
            val machine = machine(brokenWriter)
            val start = machine.decide(machine.initialSnapshot(Context()), MachineInput.Start(MachineTime.Zero)).snapshot
            val action: Input = if (brokenWriter) Input.Search("P") else Input.InvalidInternal
            val decision = machine.decide(start, MachineInput.Dispatch(action, MachineTime.Zero))
            assertIs<DecisionOutcome.Failed>(decision.outcome)
            assertEquals(start, decision.snapshot)
            assertTrue(decision.snapshot.commands.isEmpty())
            assertTrue(decision.commands.isEmpty())
        }
    }

    @Test
    fun duplicateStateDeclarationsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Context, Phase, Input, String, Output>(
                DefinitionId("duplicate"),
                DefinitionVersion("1"),
                Phase.Empty,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state<Phase.Empty>()
                state<Phase.Empty>()
            }
        }
    }

    @Test
    fun missingTargetIsRejectedAtDefinitionTime() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Context, Phase, Input, String, Output>(
                DefinitionId("invalid"),
                DefinitionVersion("1"),
                Phase.Empty,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state<Phase.Empty> { on<Input.Search, Phase.Loading> { _, _ -> Phase.Loading } }
            }
        }
    }
}
