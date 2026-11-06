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
        val query: String get() = "old"
        val revision: Int get() = 4

        data class Loading(
            override val revision: Int = 4,
        ) : Phase

        data class Success(
            val cities: List<String>,
            override val query: String,
            override val revision: Int,
        ) : Phase

        data object Error : Phase
    }

    private data class Found(
        val revision: Int,
        val cities: List<String>,
    ) : Action

    @Test
    fun graphIsKnownWithoutRunningRulesAndBothGuardOutcomesAreExplained() {
        var evaluated = 0
        val machine =
            typedStateMachine<Phase, Found, Nothing, Nothing>(
                id = DefinitionId(value = "declared"),
                version = DefinitionVersion(value = "1"),
                initial = Phase.Loading(),
            ) {
                state<Phase.Loading> {
                    on<Found> {
                        guard { action -> action.revision == state.revision }
                        target { action ->
                            evaluated++
                            Phase.Success(cities = action.cities, query = state.query, revision = state.revision)
                        }
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
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Phase.Loading()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
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
        assertEquals(expected = Phase.Success(cities = listOf("Paris"), query = "old", revision = 4), actual = accepted.decision.snapshot.context)
        assertTrue(actual = accepted.decision.snapshot.isActive(id = StateId(value = "Success")))
        assertTrue(
            actual =
                accepted.decision.snapshot.timers
                    .isEmpty(),
        )
        assertEquals(expected = 1, actual = evaluated)
    }

    @Test
    fun targetFailureRollsBackWholeDecision() {
        val machine =
            machine {
                on<Found> {
                    target<Phase.Success> { error("Failed to build next state") }
                }
            }
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Phase.Loading()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val result = machine.decide(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 4, cities = emptyList()), now = MachineTime.Zero))
        assertIs<DecisionOutcome.Failed>(value = result.outcome)
        assertEquals(expected = start, actual = result.snapshot)
    }

    @Test
    fun exitHooksKeepTheirOrderBeforeTarget() {
        val observed = mutableListOf<String>()
        val machine =
            machine {
                onExit { observed += "exit:${state.revision}" }
                on<Found> {
                    guard { state.revision == 4 }
                    target {
                        observed += "target:${state.revision}"
                        Phase.Success(cities = emptyList(), query = state.query, revision = state.revision + 1)
                    }
                }
            }
        val start = machine.decide(snapshot = machine.initialSnapshot(context = Phase.Loading()), input = MachineInput.Start(now = MachineTime.Zero)).snapshot
        val result = machine.decide(snapshot = start, input = MachineInput.Dispatch(action = Found(revision = 4, cities = emptyList()), now = MachineTime.Zero))
        assertEquals(listOf("exit:4", "target:4"), observed)
        assertEquals(expected = Phase.Success(cities = emptyList(), query = "old", revision = 5), actual = result.snapshot.context)
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
                    target { Phase.Loading() }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { machine { on<Found> { target<Phase> { Phase.Error } } } }
    }

    private fun machine(build: TypedMachineStateBuilder<Phase, Phase, Found, Nothing, Nothing>.() -> Unit) =
        typedStateMachine<Phase, Found, Nothing, Nothing>(
            id = DefinitionId(value = "validation"),
            version = DefinitionVersion(value = "1"),
            initial = Phase.Loading(),
        ) {
            state<Phase.Loading>(build = build)
            state<Phase.Success>()
            state<Phase.Error>()
        }
}
