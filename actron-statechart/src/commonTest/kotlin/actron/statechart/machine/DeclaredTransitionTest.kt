package actron.statechart.machine

import actron.core.Action
import actron.statechart.GuardCheck
import actron.statechart.GuardCondition
import actron.statechart.StateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DeclaredTransitionTest {
    private sealed interface Phase {
        data object Loading : Phase

        data class Success(
            val cities: List<String>,
            val query: String,
            val revision: Int,
        ) : Phase

        data object Error : Phase
    }

    private data class Context(
        val phase: Phase = Phase.Loading,
        val query: String = "old",
        val revision: Int = 4,
    )

    private data class Found(
        val revision: Int,
        val cities: List<String>,
    ) : Action

    @Test
    fun graphIsKnownWithoutRunningRulesAndBothGuardOutcomesAreExplained() {
        var evaluated = 0
        val machine =
            typedStateMachine<Context, Phase, Found, Nothing, Nothing>(
                id = DefinitionId(value = "declared"),
                version = DefinitionVersion(value = "1"),
                initial = Phase.Loading,
                readState = { it.phase },
                writeState = { state, phase -> state.copy(phase = phase) },
            ) {
                state<Phase.Loading> {
                    on<Found> {
                        guard { action -> action.revision == state.revision }
                        target { action ->
                            evaluated++
                            Phase.Success(cities = action.cities, query = state.query, revision = state.revision)
                        }
                        update { state.copy(query = "new", revision = state.revision + 1) }
                    }
                    after(delay = 350.milliseconds, transition = { target { Phase.Error } })
                }
                state<Phase.Success>()
                state<Phase.Error>()
            }
        assertEquals(expected = 0, actual = evaluated)
        assertEquals(expected = listOf("Loading->Success", "Loading->Error"), actual = machine.chart.transitions.map { "${it.source.value}->${it.target.value}" })
        assertEquals(
            expected = GuardCondition.Unconditional,
            actual =
                machine.chart.transitions
                    .last()
                    .guard,
        )
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Context()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val rejected = machine.decideExplained(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 3, cities = emptyList()), now = MachineTime.Zero))
        assertEquals(expected = start, actual = rejected.decision.snapshot)
        assertEquals(
            expected = GuardCheck.Rejected,
            actual =
                rejected.explanation.guards
                    .single()
                    .result,
        )
        assertEquals(expected = 0, actual = evaluated)
        val accepted = machine.decideExplained(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 4, cities = listOf("Paris")), now = MachineTime.Zero))
        assertEquals(
            expected = GuardCheck.Allowed,
            actual =
                accepted.explanation.guards
                    .single()
                    .result,
        )
        assertEquals(expected = Context(phase = Phase.Success(cities = listOf("Paris"), query = "old", revision = 4), query = "new", revision = 5), actual = accepted.decision.snapshot.context)
        assertTrue(actual = accepted.decision.snapshot.isActive(id = StateId(value = "Success")))
        assertTrue(
            actual =
                accepted.decision.snapshot.timers
                    .isEmpty(),
        )
        assertEquals(expected = 1, actual = evaluated)
    }

    @Test
    fun updateCannotChangePhaseAndFailureRollsBackWholeDecision() {
        val machine =
            machine {
                on<Found> {
                    target { Phase.Success(cities = emptyList(), query = state.query, revision = state.revision) }
                    update { state.copy(phase = Phase.Error, query = "must not commit") }
                }
            }
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Context()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val result = machine.decide(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 4, cities = emptyList()), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Failed>(value = result.outcome)
        assertEquals(expected = start, actual = result.snapshot)
    }

    @Test
    fun exitHooksKeepTheirOrderBeforeTargetAndUpdate() {
        val machine =
            machine {
                onExit(block = { context = context.copy(revision = 10) })
                on<Found> {
                    guard { state.revision == 4 }
                    target { Phase.Success(cities = emptyList(), query = state.query, revision = state.revision) }
                    update { state.copy(revision = state.revision + 1) }
                }
            }
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Context()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val result = machine.decide(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 4, cities = emptyList()), now = MachineTime.Zero))
        assertEquals(expected = Context(phase = Phase.Success(cities = emptyList(), query = "old", revision = 10), revision = 11), actual = result.snapshot.context)
    }

    @Test
    fun timerInfersTargetFromItsDeclaration() {
        val machine = machine { after(delay = 350.milliseconds) { target { Phase.Error } } }
        assertEquals(
            expected = StateId(value = "Error"),
            actual =
                machine.chart.transitions
                    .single()
                    .target,
        )
    }

    @Test
    fun missingDuplicateOrBroadTargetsAreRejectedWhileBuilding() {
        assertFailsWith<IllegalArgumentException> { machine { on<Found> { guard { true } } } }
        assertFailsWith<IllegalArgumentException> {
            machine {
                on<Found> {
                    target { Phase.Error }
                    target { Phase.Loading }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { machine { on<Found> { target<Phase> { Phase.Error } } } }
    }

    private fun machine(build: TypedMachineStateBuilder<Context, Phase, Found, Nothing, Nothing>.() -> Unit) =
        typedStateMachine<Context, Phase, Found, Nothing, Nothing>(
            id = DefinitionId(value = "validation"),
            version = DefinitionVersion(value = "1"),
            initial = Phase.Loading,
            readState = { it.phase },
            writeState = { state, phase -> state.copy(phase = phase) },
        ) {
            state<Phase.Loading>(build = build)
            state<Phase.Success>()
            state<Phase.Error>()
        }
}
