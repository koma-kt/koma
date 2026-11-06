package actron.decompose

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.noCommandHandler
import actron.statechart.machine.resumeMachineStore
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Creates, configures, retains and starts a machine in one call. The keeper owns all Store work.
 * Use a unique stable key per machine/business identity; a retained key keeps its original inputs.
 * Handlers and recording options must not capture a component, UI scope or bridge endpoint.
 * Initial actions run once for a fresh instance; resumed checkpoints never repeat them.
 */
fun <C : Any, A : Action, E : Event> ComponentContext.retainedMachine(
    key: String,
    machine: ScreenMachine<C, A, E>,
    initial: C,
    restoration: MachineRestoration<C, A, Unit, E> = MachineRestoration { definition, context -> ExecutorCheckpoint.initial(definition.initialSnapshot(context)) },
    recording: MachineRecording<C, A, Unit, E> =
        MachineRecording { _, _, commands -> MachineDiagnostics(commands) },
    coroutineContext: CoroutineContext = Dispatchers.Main,
    initialActions: List<A> = emptyList(),
): MachineStore<C, A, Unit, E> = retainedMachine(key, machine, initial, noCommandHandler(), restoration, recording, coroutineContext, initialActions)

fun <C : Any, A : Action, CMD : Any, E : Event> ComponentContext.retainedMachine(
    key: String,
    machine: Machine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
    restoration: MachineRestoration<C, A, CMD, E> = MachineRestoration { definition, context -> ExecutorCheckpoint.initial(definition.initialSnapshot(context)) },
    recording: MachineRecording<C, A, CMD, E> =
        MachineRecording { _, _, commands -> MachineDiagnostics(commands) },
    coroutineContext: CoroutineContext = Dispatchers.Main,
    initialActions: List<A> = emptyList(),
): MachineStore<C, A, CMD, E> =
    instanceKeeper
        .getOrCreate(key = key) {
            val checkpoint = restoration.restore(machine, initial)
            require(checkpoint.snapshot.definition == machine.id && checkpoint.snapshot.version == machine.version) { "Checkpoint belongs to a different machine" }
            val setup = recording.prepare(machine, checkpoint, handler)
            val observers = restoration.observers(machine, checkpoint) + setup.observers
            MachineInstance(coroutineContext) { scope ->
                if (checkpoint.snapshot.isStarted) {
                    resumeMachineStore(machine, checkpoint, setup.handler, scope, coroutineContext, restoration.clock, observers, builder = setup.configure)
                } else {
                    MachineStore(machine, checkpoint.snapshot.context, setup.handler, scope, restoration.clock, coroutineContext, observers = observers, builder = setup.configure)
                }
            }.also { instance ->
                if (!checkpoint.snapshot.isStarted) initialActions.forEach(instance.store::dispatch)
            }
        }.store
