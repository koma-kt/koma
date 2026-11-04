package actron.example.weather

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DecisionTraceObserver
import actron.statechart.machine.EntryOutput
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.test.*
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Suspend behavior is exercised with the real executor; traces can still be replayed without I/O. */
object GeneratedWeatherScenarios {
    val moscow = City(name = "Москва", latitude = 55.75, longitude = 37.62)
    val kazan = City(name = "Казань", latitude = 55.79, longitude = 49.12)

    suspend fun search(scope: TestScope) =
        recorded(
            machine = CitySearchMachine.create(),
            context = CitySearchState(),
            scope = scope,
        ) { trace ->
            send(action = CitySearchAction.ChangeQuery(query = moscow.name))
            advanceBy(duration = 350.milliseconds)
            val old =
                snapshot.pendingEntries.values
                    .single()
                    .activation
            val oldRevision = snapshot.context.revision
            send(action = CitySearchAction.ChangeQuery(query = kazan.name))
            advanceBy(duration = 350.milliseconds)
            val lateAction = CitySearchAction.Found(revision = oldRevision, results = listOf(CityOption(city = moscow)))
            val lateInput = MachineInput.EntryResult(activation = old, output = EntryOutput.Dispatch(action = lateAction), now = MachineTime(sinceStart = scope.testScheduler.currentTime.milliseconds))
            // Inject an obsolete worker outcome through the same pure decision entrypoint as the queue.
            val rejected = machine.decideExplained(snapshot = snapshot, input = lateInput)
            assertIs<DecisionOutcome.Ignored>(value = rejected.decision.outcome)
            trace.onDecided(input = InputAttribution.Unattributed, machineInput = lateInput, explained = rejected)
            send(action = lateAction)
            assertIs<RequestState.Loading>(value = snapshot.context.phase)
            val request = runningCommands.single()
            answer(command = request.id, result = CitySearchAction.Found(revision = request.command.revision, results = listOf(CityOption(city = kazan))))
            assertEquals(expected = kazan, actual = assertIs<RequestState.Success<List<CityOption>>>(value = snapshot.context.phase).value.single().city)
            assertTrue(actual = handler.cancelled.isNotEmpty())
        }

    suspend fun weather(scope: TestScope) =
        recorded(
            machine = CurrentWeatherMachine.create(),
            context = CurrentWeatherState(city = kazan),
            scope = scope,
        ) { _ ->
            send(action = CurrentWeatherAction.Refresh)
            answer(command = runningCommands.single().id, result = CurrentWeatherAction.Loaded(reading = WeatherReading(city = kazan, temperature = 12.0, feelsLike = 10.0, wind = 3.0, code = 0, time = "12:00")))
            assertIs<RequestState.Success<WeatherReading>>(value = snapshot.context.phase)
        }
}

private suspend fun <C : Any, A : Action, CMD : Any, E : Event> recorded(
    machine: Machine<C, A, CMD, E>,
    context: C,
    scope: TestScope,
    block: suspend MachineTestDriver<C, A, CMD, E>.(ExecutedTrace<C, A, CMD, E>) -> Unit,
): ScenarioTrace<C, A, CMD, E> {
    val initial = machine.initialSnapshot(context = context)
    val trace = ExecutedTrace<C, A, CMD, E>(initial = initial)
    val driver = MachineTestDriver(machine = machine, context = context, scope = scope, observers = listOf(trace))
    try {
        driver.start()
        driver.block(trace)
        assertTrue(actual = driver.failures.isEmpty())
        return ScenarioTrace(scenario = MachineScenario(name = machine.id.value, inputs = trace.steps.map { it.input }), initial = initial, steps = trace.steps.toList(), coverage = driver.coverage, failures = emptyList())
    } finally {
        driver.close()
    }
}

private class ExecutedTrace<C : Any, A : Action, CMD : Any, E : Event>(
    initial: MachineSnapshot<C>,
) : DecisionTraceObserver<C, A, CMD, E> {
    private var snapshot = initial
    val steps = mutableListOf<ScenarioStep<C, A, CMD, E>>()

    override fun onDecided(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        explained: ExplainedDecision<C, CMD, E>,
    ) {
        steps += ScenarioStep(before = snapshot, input = machineInput, explained = explained)
        snapshot = explained.decision.snapshot
    }
}
