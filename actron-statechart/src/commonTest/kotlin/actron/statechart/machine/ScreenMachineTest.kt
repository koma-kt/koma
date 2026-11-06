package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenMachineTest {
    private sealed interface State {
        data object Loading : State

        data class Done(
            val result: String,
        ) : State
    }

    private data class Finish(
        val result: String,
    ) : Action

    private sealed interface Output : Event

    @Test
    fun entryDispatchesThroughTheDeclaredGraphAndIsCancelledOnExit() =
        runTest {
            val stopped = CompletableDeferred<Unit>()
            val machine =
                screenMachine<State, Finish, Output>(DefinitionId("screen"), DefinitionVersion("1"), State.Loading) {
                    state<State.Loading> {
                        onEnter {
                            try {
                                dispatch(Finish("result"))
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                        on<Finish> { target { action -> State.Done(action.result) } }
                    }
                    state<State.Done>()
                }
            val store = MachineStore(machine, State.Loading, backgroundScope, coroutineContext = coroutineContext)
            try {
                store.start()
                assertEquals(State.Done("result"), store.state.first { it.context is State.Done }.context)
                stopped.await()
            } finally {
                store.closeAndJoin()
            }
        }
}
