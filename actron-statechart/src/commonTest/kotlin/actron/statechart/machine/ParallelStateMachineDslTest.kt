package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ParallelStateMachineDslTest {
    private sealed interface Phase {
        data object Idle : Phase

        data object Loading : Phase

        data class Ready(
            val value: Int,
        ) : Phase

        data object Timeout : Phase
    }

    private data class Context(
        val left: Phase = Phase.Idle,
        val right: Phase = Phase.Idle,
    )

    private sealed interface Input : Action {
        data object Load : Input

        data object RetryLeft : Input

        data class LeftDone(
            val value: Int,
        ) : Input

        data class RightDone(
            val value: Int,
        ) : Input
    }

    private sealed interface Output : Event

    private fun machine() =
        parallelStateMachine<Context, Input, String, Output>(DefinitionId("parallel"), DefinitionVersion("1")) {
            region("Left", Phase.Idle, { it.left }, { context, state: Phase -> context.copy(left = state) }) {
                state<Phase.Idle> { on<Input.Load, Phase.Loading> { _, _ -> Phase.Loading } }
                state<Phase.Loading> {
                    onEnter { command("left") }
                    on<Input.LeftDone, Phase.Ready> { _, action -> Phase.Ready(action.value) }
                    on<Input.RetryLeft, Phase.Loading> { _, _ -> Phase.Loading }
                    after(delay = 2.seconds) { target { Phase.Timeout } }
                }
                state<Phase.Ready>()
                state<Phase.Timeout>()
            }
            region("Right", Phase.Idle, { it.right }, { context, state: Phase -> context.copy(right = state) }) {
                state<Phase.Idle> { on<Input.Load, Phase.Loading> { _, _ -> Phase.Loading } }
                state<Phase.Loading> {
                    onEnter { command("right") }
                    on<Input.RightDone, Phase.Ready> { _, action -> Phase.Ready(action.value) }
                    after(delay = 5.seconds) { target { Phase.Timeout } }
                }
                state<Phase.Ready>()
                state<Phase.Timeout>()
            }
        }

    @Test
    fun oneInputStartsBothRegions_andRetryDoesNotCancelItsSibling() {
        val machine = machine()
        val start = machine.decide(machine.initialSnapshot(Context()), MachineInput.Start(MachineTime.Zero)).snapshot
        val load = machine.decide(start, MachineInput.Dispatch(Input.Load, MachineTime.Zero))
        assertEquals(Context(Phase.Loading, Phase.Loading), load.snapshot.context)
        assertEquals(setOf("left", "right"), load.commands.map { it.command }.toSet())
        val right = load.commands.single { it.command == "right" }
        val retry = machine.decide(load.snapshot, MachineInput.Dispatch(Input.RetryLeft, MachineTime.Zero))
        assertTrue(right.scope !in retry.cancelledScopes)
        assertEquals(listOf("left"), retry.commands.map { it.command })
        assertEquals(load.snapshot.commands.getValue(right.id), retry.snapshot.commands.getValue(right.id))
        val answer = machine.decide(retry.snapshot, MachineInput.Dispatch(Input.RightDone(42), MachineTime.Zero))
        assertEquals(Context(Phase.Loading, Phase.Ready(42)), answer.snapshot.context)
        assertTrue(answer.snapshot.isActive(StateId("Left.Loading")))
        assertTrue(answer.snapshot.isActive(StateId("Right.Ready")))
        val timer =
            answer.snapshot.timers.keys
                .single()
        val timeout = machine.decide(answer.snapshot, MachineInput.TimerFired(timer, MachineTime(2.seconds)))
        assertEquals(Context(Phase.Timeout, Phase.Ready(42)), timeout.snapshot.context)
        assertEquals(emptyList(), machine.checkInvariants(timeout.snapshot))
        assertEquals(timeout, machine().decide(answer.snapshot, MachineInput.TimerFired(timer, MachineTime(2.seconds))))
    }
}
