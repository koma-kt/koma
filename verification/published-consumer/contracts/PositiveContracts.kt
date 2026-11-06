package consumer

import actron.core.ExceptionHandler
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineStore
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.screenMachine
import kotlinx.coroutines.CoroutineScope

fun configuredMachine(scope: CoroutineScope) =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> }, scope, coroutineContext = scope.coroutineContext) {
        exceptionHandler(ExceptionHandler.Ignore)
    }

fun screenWithDirectEntry(load: suspend () -> Unit): ScreenMachine<Counter, Increment, Ev> =
    screenMachine(DefinitionId("screen-consumer"), DefinitionVersion("1"), Counter()) {
        state<Counter> {
            onEnter {
                load()
                event(Ev)
            }
        }
    }
