package actron.example.weather

import actron.core.Action
import actron.core.Event
import actron.example.weather.app.WeatherMachines
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.freshWeather
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WeatherHierarchyTest {
    @Test
    fun eachStandaloneChildStartsReadyWithoutRequestsOrTimers() {
        val city = demoCities.first()
        assertReady(machine = CurrentWeatherMachine.create(), context = CurrentWeatherState(city = city))
        assertReady(machine = HourlyForecastMachine.create(), context = HourlyForecastState(city = city))
        assertReady(machine = WeatherAdviceMachine.create(), context = WeatherAdviceState(city = city))
    }

    @Test
    fun parentRefreshReloadsSuccessErrorAndDismissedChildrenTogether() =
        runTest(testBody = {
            val city = demoCities.first()
            var currentCalls = 0
            var hourlyCalls = 0
            val machines =
                WeatherMachines(
                    session = WeatherSession(clockFactory = { MachineClock.monotonic(timeSource = testScheduler.timeSource) }),
                    initial = freshWeather(city = city),
                    currentRepository =
                        WeatherRepository { selected ->
                            if (++currentCalls > 2) awaitCancellation()
                            WeatherReading(city = selected, temperature = 15.0, feelsLike = 15.0, wind = 2.0, code = 61, time = "12:00")
                        },
                    hourlyRepository =
                        HourlyForecastRepository {
                            if (++hourlyCalls == 1) error("offline")
                            awaitCancellation()
                        },
                    coroutineContext = coroutineContext,
                )
            try {
                machines.start()
                testScheduler.runCurrent()
                assertIs<RequestState.Success<WeatherReading>>(value = machines.current.state.value.context.phase)
                assertIs<RequestState.Error>(value = machines.hourly.state.value.context.phase)
                machines.advice.dispatch(action = WeatherAdviceAction.Dismiss)
                testScheduler.runCurrent()
                assertEquals(expected = WeatherAdvicePhase.Dismissed, actual = machines.advice.state.value.context.phase)
                machines.screen.dispatch(action = WeatherScreenAction.Refresh)
                testScheduler.runCurrent()
                assertEquals(expected = 4, actual = currentCalls)
                assertEquals(expected = 2, actual = hourlyCalls)
                assertEquals(expected = RequestState.Loading, actual = machines.current.state.value.context.phase)
                assertEquals(expected = RequestState.Loading, actual = machines.hourly.state.value.context.phase)
                assertEquals(expected = WeatherAdvicePhase.Loading, actual = machines.advice.state.value.context.phase)
                assertEquals(expected = city, actual = machines.screen.state.value.context.city)
            } finally {
                machines.onDestroy()
            }
        })

    @Test
    fun repeatedRefreshCancelsAllOldRequestsAndDismissCancelsOnlyAdvice() =
        runTest(testBody = {
            var started = 0
            var cancelled = 0

            suspend fun pending(): Nothing {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
            val machines =
                WeatherMachines(
                    session = WeatherSession(clockFactory = { MachineClock.monotonic(timeSource = testScheduler.timeSource) }),
                    initial = freshWeather(city = demoCities.first()),
                    currentRepository = WeatherRepository { pending() },
                    hourlyRepository = HourlyForecastRepository { pending() },
                    coroutineContext = coroutineContext,
                )
            try {
                machines.start()
                testScheduler.runCurrent()
                assertEquals(expected = 3, actual = started)
                machines.screen.dispatch(action = WeatherScreenAction.Refresh)
                testScheduler.runCurrent()
                assertEquals(expected = 6, actual = started)
                assertEquals(expected = 3, actual = cancelled)
                machines.advice.dispatch(action = WeatherAdviceAction.Dismiss)
                testScheduler.runCurrent()
                assertEquals(expected = 4, actual = cancelled)
                assertEquals(expected = RequestState.Loading, actual = machines.current.state.value.context.phase)
                assertEquals(expected = RequestState.Loading, actual = machines.hourly.state.value.context.phase)
                machines.screen.dispatch(action = WeatherScreenAction.Refresh)
                testScheduler.runCurrent()
                assertEquals(expected = 9, actual = started)
                assertEquals(expected = 6, actual = cancelled)
            } finally {
                machines.onDestroy()
                testScheduler.runCurrent()
            }
            assertEquals(expected = 9, actual = cancelled)
        })

    @Test
    fun adviceRejectsLateResultAfterItsOwnDismissAction() {
        val machine = WeatherAdviceMachine.create()
        val city = demoCities.first()
        val ready = machine.decide(snapshot = machine.initialSnapshot(context = WeatherAdviceState(city = city)), input = MachineInput.Start(now = MachineTime.Zero))
        val loading = machine.decide(snapshot = ready.snapshot, input = MachineInput.Dispatch(action = WeatherAdviceAction.Refresh, now = MachineTime.Zero))
        val request =
            loading.snapshot.pendingEntries.values
                .single()
        val dismissed = machine.decide(snapshot = loading.snapshot, input = MachineInput.Dispatch(action = WeatherAdviceAction.Dismiss, now = MachineTime.Zero))
        val late =
            machine.decide(
                snapshot = dismissed.snapshot,
                input =
                    MachineInput.EntryResult(
                        activation = request.activation,
                        output =
                            actron.statechart.machine.EntryOutput
                                .Dispatch(action = WeatherAdviceAction.Loaded(city = city, items = listOf(WeatherAdvice.Umbrella))),
                        now = MachineTime.Zero,
                    ),
            )
        assertEquals(expected = dismissed.snapshot, actual = late.snapshot)
        assertEquals(expected = WeatherAdvicePhase.Dismissed, actual = late.snapshot.context.phase)
        assertTrue(actual = request.activation in dismissed.cancelledScopes)
    }
}

private fun <C : Any, A : Action, CMD : Any, E : Event> assertReady(
    machine: Machine<C, A, CMD, E>,
    context: C,
) {
    val decision = machine.decide(snapshot = machine.initialSnapshot(context = context), input = MachineInput.Start(now = MachineTime.Zero))
    assertTrue(actual = decision.commands.isEmpty())
    assertTrue(actual = decision.snapshot.timers.isEmpty())
    assertEquals(
        expected = listOf("Ready"),
        actual =
            decision.snapshot.configuration.active
                .map { it.value },
    )
}
