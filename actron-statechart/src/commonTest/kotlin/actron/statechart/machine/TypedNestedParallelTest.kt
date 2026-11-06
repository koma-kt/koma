package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypedNestedParallelTest {
    private sealed interface Phase {
        data object Paused : Phase

        data object Active : Phase

        data class Running(
            val left: Step = Step.Loading,
            val right: Step = Step.Loading,
        ) : Phase

        data object Done : Phase

        data object Finished : Phase
    }

    private sealed interface Step {
        data object Loading : Step

        data object Done : Step
    }

    private data object Left

    private data object Right

    private sealed interface Act : Action {
        data object Resume : Act

        data object Pause : Act

        data object LeftDone : Act

        data object RightDone : Act
    }

    private sealed interface Ev : Event

    private fun machine(deep: Boolean) =
        typedStateMachine<Phase, Act, String, Ev>(
            DefinitionId("nested-parallel"),
            DefinitionVersion("1"),
            Phase.Paused,
        ) {
            state(Phase.Paused) { onHistory<Act.Resume, Phase.Active>() }
            compound<Phase.Active>(Phase.Running()) {
                history(deep)
                on<Act.Pause, Phase.Paused>()
                onDone<Phase.Finished>()
                parallel(Phase.Running()) {
                    onDone<Phase.Done>()
                    region<Left, Step>(Step.Loading, { (it as Phase.Running).left }, { c, step -> (c as Phase.Running).copy(left = step) }) {
                        state(Step.Loading) {
                            onEnter { command("left") }
                            on<Act.LeftDone, Step.Done>()
                        }
                        final(Step.Done)
                    }
                    region<Right, Step>(Step.Loading, { (it as Phase.Running).right }, { c, step -> (c as Phase.Running).copy(right = step) }) {
                        state(Step.Loading) {
                            onEnter { command("right") }
                            on<Act.RightDone, Step.Done>()
                        }
                        final(Step.Done)
                    }
                }
                final(Phase.Done)
            }
            final(Phase.Finished)
        }

    @Test
    fun nestedParallelHistoryKeepsTypedBindingsAndCancellationConsistent() {
        for (deep in listOf(false, true)) {
            val machine = machine(deep)
            var snapshot = machine.decide(machine.initialSnapshot(Phase.Paused), MachineInput.Start(MachineTime.Zero)).snapshot

            fun send(action: Act): Decision<Phase, String, Ev> {
                val decision = machine.decide(snapshot, MachineInput.Dispatch(action, MachineTime.Zero))
                assertIs<DecisionOutcome.Handled>(decision.outcome)
                assertTrue(machine.checkInvariants(decision.snapshot).isEmpty())
                snapshot = decision.snapshot
                return decision
            }
            assertEquals(2, send(Act.Resume).commands.size)
            assertTrue(snapshot.context is Phase.Running)
            assertTrue(snapshot.isActive(StateId("Running.Left.Loading")))
            send(Act.LeftDone)
            assertEquals(Step.Done, (snapshot.context as Phase.Running).left)
            assertFalse(machine.isComplete(snapshot))
            val paused = send(Act.Pause)
            assertTrue(paused.cancelledScopes.isNotEmpty())
            assertTrue(snapshot.commands.isEmpty())
            assertEquals(Phase.Paused, snapshot.context)
            val resumed = send(Act.Resume)
            assertEquals(if (deep) 1 else 2, resumed.commands.size)
            assertEquals(if (deep) Step.Done else Step.Loading, (snapshot.context as Phase.Running).left)
            if (!deep) send(Act.LeftDone)
            val completed = send(Act.RightDone)
            assertEquals(Phase.Finished, snapshot.context)
            assertTrue(machine.isComplete(snapshot))
            assertTrue(completed.transitions.size >= 3)
            assertTrue(snapshot.commands.isEmpty())
        }
    }

    @Test
    fun incompleteParallelDeclarationFailsEarly() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Phase, Act, String, Ev>(
                DefinitionId("empty-parallel"),
                DefinitionVersion("1"),
                Phase.Running(),
            ) { parallel(Phase.Running()) {} }
        }
    }
}
