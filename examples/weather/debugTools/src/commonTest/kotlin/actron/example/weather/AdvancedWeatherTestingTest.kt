package actron.example.weather

import actron.example.weather.core.mvi.*
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.Trigger
import actron.statechart.machine.*
import actron.statechart.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Developer-facing examples: finite data, temporal obligations, real executor schedules and mutant oracles. */
class AdvancedWeatherTestingTest {
    private val moscow = GeneratedWeatherScenarios.moscow
    private val kazan = GeneratedWeatherScenarios.kazan

    private fun reading(city: actron.example.weather.core.domain.City) = WeatherReading(city, 12.0, 10.0, 3.0, 0, "2026-10-20T12:00")

    @Test fun searchLeavesLoadingWithinItsTimeoutIncludingCancellation() =
        runTest {
            CitySearchMachine.create().test(scope = this, initial = CitySearchState()) {
                dispatch(action = CitySearchAction.ChangeQuery(query = kazan.name))
                advanceTimeBy(duration = 350.milliseconds)
                expectState<RequestState.Loading>(select = { it.phase })
                advanceTimeBy(duration = 7999.milliseconds)
                expectState<RequestState.Loading>(select = { it.phase })
                advanceTimeBy(duration = 1.milliseconds)
                assertEquals(expected = RequestFailure.Timeout, actual = expectState<RequestState.Error>(select = { it.phase }).cause)
                assertTrue(actual = driver.runningCommands.isEmpty())
            }
        }

    @Test fun actualCurrentExecutorExploresResultFailureAndTimeout() =
        runTest(testBody = {
            val report =
                CurrentWeatherMachine.create().exploreSchedules(
                    initial = CurrentWeatherState(city = kazan),
                    scope = this,
                    maxDepth = 1,
                    maxRuns = 20,
                    setup = { send(action = CurrentWeatherAction.Refresh) },
                    choices = {
                        if (runningCommands.isEmpty()) {
                            emptyList()
                        } else {
                            runningCommands.flatMap { command ->
                                listOf(
                                    ScheduleOperation.Answer(command = command.id, action = CurrentWeatherAction.Loaded(reading = reading(city = kazan))),
                                    ScheduleOperation.Answer(command = command.id, action = CurrentWeatherAction.Failed(message = "offline")),
                                )
                            } + ScheduleOperation.Advance(duration = 8.seconds)
                        }
                    },
                    check = {
                        val state = snapshot.context.phase
                        if (state is RequestState.Success) assertEquals(expected = kazan, actual = state.value.city)
                    },
                )
            report.assertSuccess()
            assertEquals(expected = 3, actual = report.completed.size)
        })

    @Test fun actualSearchExecutorExploresAnswerBeforeAndAfterQueryReplacement() =
        runTest {
            val report =
                CitySearchMachine.create().exploreSchedules(
                    CitySearchState(),
                    this,
                    maxDepth = 4,
                    maxRuns = 20,
                    setup = {
                        send(CitySearchAction.ChangeQuery(moscow.name))
                        advanceBy(350.milliseconds)
                    },
                    choices = {
                        val state = snapshot.context
                        buildList {
                            if (state.query == moscow.name) add(ScheduleOperation.Send(CitySearchAction.ChangeQuery(kazan.name)))
                            if (state.phase is RequestState.Debouncing) add(ScheduleOperation.Advance(350.milliseconds))
                            for (command in runningCommands) {
                                val city = if (command.command.query == moscow.name) moscow else kazan
                                add(ScheduleOperation.Answer(command.id, CitySearchAction.Found(command.command.revision, listOf(CityOption(city)))))
                            }
                        }
                    },
                    check = {
                        val state = snapshot.context
                        if (state.query == kazan.name) assertTrue(handler.started.filter { it.command.query == moscow.name }.all { it.id in handler.cancelled })
                        val phase = state.phase
                        if (phase is RequestState.Success) assertTrue(phase.value.all { it.city.name == state.query })
                    },
                )
            report.assertSuccess()
            assertEquals(2, report.completed.size)
        }

    @Test fun currentWeatherRejectsOtherCityThenAcceptsItsOwnCity() =
        runTest {
            CurrentWeatherMachine.create().test(scope = this, initial = CurrentWeatherState(city = kazan)) {
                dispatch(action = CurrentWeatherAction.Refresh)
                dispatch(action = CurrentWeatherAction.Loaded(reading = reading(city = moscow)))
                expectState<RequestState.Loading>(select = { it.phase })
                dispatch(action = CurrentWeatherAction.Loaded(reading = reading(city = kazan)))
                assertEquals(expected = kazan, actual = expectState<RequestState.Success<WeatherReading>>(select = { it.phase }).value.city)
            }
        }

    @Test fun executorExploresRestartDuringDebounceAndDuringHttp() =
        runTest {
            val report =
                CitySearchMachine.create().exploreSchedules(
                    CitySearchState(),
                    this,
                    maxDepth = 4,
                    maxRuns = 40,
                    setup = { send(CitySearchAction.ChangeQuery(kazan.name)) },
                    choices = { path ->
                        buildList {
                            if (snapshot.context.phase is RequestState.Debouncing) add(ScheduleOperation.Advance(350.milliseconds))
                            for (command in runningCommands) add(ScheduleOperation.Answer(command.id, CitySearchAction.Found(command.command.revision, listOf(CityOption(kazan)))))
                            if (snapshot.context.phase !is RequestState.Success && ScheduleOperation.Restart !in path) add(ScheduleOperation.Restart)
                        }
                    },
                    check = { assertEquals(kazan.name, snapshot.context.query) },
                )
            report.assertSuccess()
            assertTrue(report.completed.any { it.first() == ScheduleOperation.Restart })
            assertTrue(report.completed.any { it.take(2) == listOf(ScheduleOperation.Advance(350.milliseconds), ScheduleOperation.Restart) })
        }
}
