package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WeatherMachinesCoverageTest {
    private enum class Phase { Idle, Debouncing, Loading, Success, Error, TimeoutError }

    private suspend fun MachineTestScenario<CitySearchState, CitySearchAction, SearchCitiesCommand, actron.example.weather.core.mvi.NoEvent>.preparePhase(phase: Phase) {
        if (phase != Phase.Idle) dispatch(CitySearchAction.ChangeQuery("London"))
        if (phase != Phase.Idle && phase != Phase.Debouncing) advanceTimeBy(350.milliseconds)
        when (phase) {
            Phase.Success -> completeCommand { CitySearchAction.Found(it.revision, emptyList()) }
            Phase.Error -> completeCommand { CitySearchAction.Failed(it.revision, "offline") }
            Phase.TimeoutError -> advanceTimeBy(8.seconds)
            else -> Unit
        }
    }

    @Test
    fun searchAllowsQueryReplacementFromEveryBusinessPhase() =
        runTest {
            val reports = mutableListOf<MachineCoverage>()
            for (phase in Phase.entries) {
                for (query in listOf("", "New query")) {
                    reports +=
                        CitySearchMachine.create().test(this, CitySearchState()) {
                            preparePhase(phase = phase)
                            dispatch(CitySearchAction.ChangeQuery(query))
                            assertEquals(query, context.query)
                            if (query.isEmpty()) {
                                expectState<RequestState.Idle> { it.phase }
                            } else {
                                expectState<RequestState.Debouncing> { it.phase }
                            }
                            // Close this scenario through a real action, leaving no timers or commands.
                            dispatch(CitySearchAction.ChangeQuery(""))
                        }
                }
            }
            val coverage = reports.reduce { a, b -> a.merge(b) }
            val chart = CitySearchMachine.create().chart
            val observed =
                coverage.transitions.covered
                    .map { id -> chart.transitions[id.index].let { "${it.source.value}->${it.target.value}" } }
                    .toSet()
            assertEquals(
                expected =
                    setOf(
                        "Idle->QueryChanged",
                        "QueryChanged->Idle",
                        "QueryChanged->Debouncing",
                        "Debouncing->Loading",
                        "Debouncing->QueryChanged",
                        "Loading->Success",
                        "Loading->Error",
                        "Loading->QueryChanged",
                        "Success->QueryChanged",
                        "Error->QueryChanged",
                    ),
                actual = observed,
            )
            println("City Search\n${coverage.describe()}")
        }

    @Test
    fun screenRefreshEmitsOneEventWithoutAnyChildDependency() =
        runTest(testBody = {
            CityWeatherMachine
                .create()
                .test(scope = this, initial = WeatherScreenState(city = demoCities.first()), scenario = {
                    driver.receiveEvent<WeatherScreenEvent.RefreshChildren>()
                    dispatch(action = WeatherScreenAction.Refresh)
                    driver.receiveEvent<WeatherScreenEvent.RefreshChildren>()
                    assertTrue(actual = driver.runningCommands.isEmpty())
                })
                .assertTransitionsCovered()
        })

    @Test
    fun searchRejectsStaleAnswersAndRestartsDebounceOnEveryEdit() =
        runTest {
            CitySearchMachine.create().test(this, CitySearchState()) {
                dispatch(CitySearchAction.ChangeQuery("L"))
                advanceTimeBy(349.milliseconds)
                dispatch(CitySearchAction.ChangeQuery("Lo"))
                advanceTimeBy(1.milliseconds)
                expectState<RequestState.Debouncing> { it.phase }
                assertTrue(driver.runningCommands.isEmpty())
                advanceTimeBy(349.milliseconds)
                expectState<RequestState.Loading> { it.phase }
                val revision = context.revision
                dispatch(CitySearchAction.Found(revision - 1, emptyList()))
                dispatch(CitySearchAction.Failed(revision - 1, "old failure"))
                expectState<RequestState.Loading> { it.phase }
                completeCommand { CitySearchAction.Found(it.revision, emptyList()) }
                assertEquals("Lo", context.query)
            }
        }

    @Test
    fun currentRejectsOtherCityResultsAndRefreshCancelsItsOldCommand() =
        runTest(testBody = {
            val city = demoCities.first()
            val other = demoCities[1]
            CurrentWeatherMachine.create().test(scope = this, initial = CurrentWeatherState(city = city), scenario = {
                dispatch(action = CurrentWeatherAction.Refresh)
                val old = driver.runningCommands.single().id
                dispatch(action = CurrentWeatherAction.Loaded(reading = WeatherReading(city = other, temperature = 1.0, feelsLike = 1.0, wind = 1.0, code = 0, time = "12:00")))
                expectState<RequestState.Loading>(select = { it.phase })
                dispatch(action = CurrentWeatherAction.Refresh)
                assertTrue(actual = old in driver.handler.cancelled)
                assertEquals(expected = 1, actual = driver.runningCommands.size)
                assertTrue(actual = driver.runningCommands.none { it.id == old })
                advanceTimeBy(duration = 8.seconds)
                expectState<RequestState.Error>(select = { it.phase })
            })
        })
}
