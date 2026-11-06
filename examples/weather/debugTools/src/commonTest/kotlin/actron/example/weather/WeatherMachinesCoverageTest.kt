package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.test.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class WeatherMachinesCoverageTest {
    private enum class Phase { Idle, Debouncing, Loading, Success, Error }

    private suspend fun MachineTestScenario<CitySearchState, CitySearchAction, Unit, NoEvent>.preparePhase(phase: Phase) {
        if (phase != Phase.Idle) dispatch(CitySearchAction.ChangeQuery("London"))
        if (phase != Phase.Idle && phase != Phase.Debouncing) advanceTimeBy(350.milliseconds)
        when (phase) {
            Phase.Success -> dispatch(CitySearchAction.Found(context.revision, emptyList()))
            Phase.Error -> dispatch(CitySearchAction.Failed(context.revision, "offline"))
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
                        CitySearchMachine.create { awaitCancellation() }.test(this, CitySearchState.Idle()) {
                            preparePhase(phase = phase)
                            dispatch(CitySearchAction.ChangeQuery(query))
                            assertEquals(query, context.query)
                            if (query.isEmpty()) {
                                expectState<CitySearchState.Idle>()
                            } else {
                                expectState<CitySearchState.Debouncing>()
                            }
                            // Close this scenario through a real action, leaving no timers or commands.
                            dispatch(CitySearchAction.ChangeQuery(""))
                        }
                }
            }
            val coverage = reports.reduce { a, b -> a.merge(b) }
            coverage.assertTransitionsCovered()
            val chart = CitySearchMachine.create { awaitCancellation() }.chart
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
                    assertTrue(actual = driver.snapshot.pendingEntries.isEmpty())
                })
                .assertTransitionsCovered()
        })

    @Test
    fun searchRejectsStaleAnswersAndRestartsDebounceOnEveryEdit() =
        runTest {
            CitySearchMachine.create { awaitCancellation() }.test(this, CitySearchState.Idle()) {
                dispatch(CitySearchAction.ChangeQuery("L"))
                advanceTimeBy(349.milliseconds)
                dispatch(CitySearchAction.ChangeQuery("Lo"))
                advanceTimeBy(1.milliseconds)
                expectState<CitySearchState.Debouncing>()
                assertTrue(driver.snapshot.pendingEntries.isEmpty())
                advanceTimeBy(349.milliseconds)
                expectState<CitySearchState.Loading>()
                val revision = context.revision
                dispatch(CitySearchAction.Found(revision - 1, emptyList()))
                dispatch(CitySearchAction.Failed(revision - 1, "old failure"))
                expectState<CitySearchState.Loading>()
                dispatch(CitySearchAction.Found(context.revision, emptyList()))
                assertEquals("Lo", context.query)
            }
        }

    @Test
    fun currentRejectsOtherCityResultsAndRefreshCancelsItsOldEntry() =
        runTest(testBody = {
            val city = demoCities.first()
            val other = demoCities[1]
            CurrentWeatherMachine.create { kotlinx.coroutines.awaitCancellation() }.test(scope = this, initial = CurrentWeatherState.Ready(city = city), scenario = {
                dispatch(action = CurrentWeatherAction.Refresh)
                val old =
                    driver.snapshot.pendingEntries.values
                        .single()
                        .activation
                dispatch(action = CurrentWeatherAction.Loaded(reading = WeatherReading(city = other, temperature = 1.0, feelsLike = 1.0, wind = 1.0, code = 0, time = "12:00")))
                expectState<CurrentWeatherState.Loading>()
                dispatch(action = CurrentWeatherAction.Refresh)
                assertTrue(
                    actual =
                        driver.snapshot.pendingEntries.values
                            .single()
                            .activation != old,
                )
                dispatch(CurrentWeatherAction.Failed("offline"))
                expectState<CurrentWeatherState.Error>()
            })
        })
}
