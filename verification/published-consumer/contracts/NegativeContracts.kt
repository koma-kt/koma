package consumer

import actron.core.ExceptionHandler
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.typedStateMachine
import kotlinx.coroutines.CoroutineScope

// Each forbidden operation has its own expected compiler diagnostic. One unrelated error must
// never make this test pass; the verifier requires a diagnostic for every labelled line.
fun forbiddenMachineConfiguration(scope: CoroutineScope) =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> }, scope) {
        state<MachineSnapshot<Counter>> {} // reject: state
        initialState(TODO()) // reject: initialState
        recover<IllegalStateException> {} // reject: recover
    }

fun forbiddenHandwrittenStore(scope: CoroutineScope) = actron.core.Store<MachineSnapshot<Counter>, Increment, Ev>(machine.initialSnapshot(Counter()), scope.coroutineContext) {} // reject: Store

fun forbiddenCoroutineChart(scope: CoroutineScope) = actron.statechart.StateChartStore<Counter, Increment, Ev>(machine.chart, Counter(), scope.coroutineContext) {} // reject: StateChartStore

fun forbiddenRuntimeBypass(scope: CoroutineScope) = actron.core.createRuntimeStore<MachineSnapshot<Counter>, Increment, Ev>(machine.initialSnapshot(Counter()), scope.coroutineContext) {} // reject: internal API

fun forbiddenRootBinding() =
    typedStateMachine<Counter, Increment, SendPing, Ev>(
        DefinitionId("root-binding"),
        DefinitionVersion("1"),
        Counter(),
        readState = { value: Counter -> value.count }, // reject: readState
        writeState = { value: Counter, count: Int -> value.copy(count = count) }, // reject: writeState
    ) { state<Counter>() }

fun forbiddenRootWriters() =
    typedStateMachine<Counter, Increment, SendPing, Ev>(
        DefinitionId("root-writers"),
        DefinitionVersion("1"),
        Counter(),
    ) {
        state<Counter> {
            on<Increment> {
                target { state.copy(count = state.count + 1) }
                update { Counter() } // reject: update
            }
            onEnter { state = Counter() } // reject: reassigned
            onExit { context = Counter() } // reject: context
        }
    }

fun forbiddenScreenCommand() =
    actron.statechart.machine.screenMachine<Counter, Increment, Ev>(
        DefinitionId("direct-entry"),
        DefinitionVersion("1"),
        Counter(),
    ) {
        state<Counter> {
            onEnter { command(SendPing) } // reject: command
        }
    }
