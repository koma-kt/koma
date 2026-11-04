package actron.example.weather

import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WeatherRootComponentTest {
    @Test
    fun continueFromWeatherRestartsOnlyPendingRegionAndRestoresSearchBackStack() =
        runTest {
            var currentCalls = 0
            var hourlyCalls = 0
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle, instanceKeeper = keeper),
                    WeatherRepository { city ->
                        currentCalls++
                        actron.example.weather.feature.weather.domain
                            .WeatherReading(city, 20.0, 19.0, 2.0, 0, "test")
                    },
                    LocalCitySearchRepository,
                    actron.example.weather.feature.weather.domain.HourlyForecastRepository {
                        hourlyCalls++
                        awaitCancellation()
                    },
                    coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val history = keeper.getOrCreate<WeatherHistory>(key = "weather-debug-history") { error("Missing history") }
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(CitySearchIntent.QueryChanged("Lis"))
                testScheduler.runCurrent()
                val searchEntry =
                    history.timeline.entries.value
                        .last()
                val frozenCoverage = searchEntry.snapshot.coverage.toMap()
                assertTrue(frozenCoverage.isNotEmpty())
                search.onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
                testScheduler.runCurrent()
                val entry =
                    history.timeline.entries.value
                        .last()
                assertTrue(entry.snapshot.application.canResume())
                assertEquals(2, currentCalls)
                assertEquals(1, hourlyCalls)
                history.panel.select(entry)
                history.panel.previewSelected()
                testScheduler.runCurrent()
                assertEquals(1, hourlyCalls, "Inspection must not restart requests")
                root.continueFrom(entry.snapshot)
                testScheduler.runCurrent()
                assertEquals(2, currentCalls, "Completed region must not reload")
                assertEquals(2, hourlyCalls, "Pending read request must restart")
                assertEquals(1L, history.session.branch)
                assertEquals(frozenCoverage, searchEntry.snapshot.coverage, "Continuing cannot mutate historical transition coverage")
                assertTrue(
                    history.timeline.entries.value
                        .contains(entry),
                )
                val weather =
                    root
                        .getChildStack()
                        .value.active.instance as actron.example.weather.feature.weather.presentation.CityWeatherComponent
                assertEquals(demoCities[1], weather.state.value.city)
                weather.onUiEvent(actron.example.weather.feature.weather.presentation.CityWeatherIntent.Back)
                testScheduler.runCurrent()
                val restored =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                assertEquals("Lis", restored.state.value.context.query)
                restored.onUiEvent(CitySearchIntent.QueryChanged("Paris"))
                testScheduler.runCurrent()
                assertEquals("Paris", restored.state.value.context.query)
                assertTrue(
                    history.timeline.entries.value
                        .all { it.machine.stableId in setOf("city-search", "city-weather", "current-weather", "hourly-forecast", "weather-advice") },
                )
                root.continueFrom(searchEntry.snapshot)
                testScheduler.runCurrent()
                val pastSearch =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                assertEquals("Lis", pastSearch.state.value.context.query)
                root.continueFrom(entry.snapshot)
                testScheduler.runCurrent()
                val pastWeather =
                    root
                        .getChildStack()
                        .value.active.instance as actron.example.weather.feature.weather.presentation.CityWeatherComponent
                assertEquals(demoCities[1], pastWeather.state.value.city)
                assertEquals(2, currentCalls, "Restoring the weather screen retains completed current weather")
                assertEquals(3, hourlyCalls, "Restoring the weather screen restarts only the pending forecast")
            } finally {
                lifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun activeWeatherRestores_andBridgeSubscriptionsDoNotDuplicateCitySelection() =
        runTest {
            val requests = mutableListOf<actron.example.weather.core.domain.City>()
            val api =
                WeatherRepository { city ->
                    requests += city
                    actron.example.weather.feature.weather.domain
                        .WeatherReading(city, 20.0, 19.0, 2.0, 0, "test")
                }
            val keeper = InstanceKeeperDispatcher()
            val stateKeeper =
                com.arkivanov.essenty.statekeeper
                    .StateKeeperDispatcher()
            val firstLifecycle = LifecycleRegistry()
            val first =
                DebugWeatherRootComponent(
                    DefaultComponentContext(firstLifecycle, instanceKeeper = keeper, stateKeeper = stateKeeper),
                    api,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    coroutineContext,
                )
            val history = keeper.getOrCreate<WeatherHistory>(key = "weather-debug-history") { error("Root must install history before creating children") }
            firstLifecycle.resume()
            testScheduler.runCurrent()
            val initialCalls = requests.size
            assertEquals(0, initialCalls, "Search must not start weather requests")
            val search =
                first
                    .getChildStack()
                    .value.active.instance as CitySearchComponent
            search.onUiEvent(CitySearchIntent.QueryChanged("Lis"))
            search.onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
            testScheduler.runCurrent()
            assertEquals(initialCalls + 2, requests.size)
            val retainedEntries = history.timeline.entries.value
            assertTrue(
                actual = retainedEntries.map { it.machine.stableId }.containsAll(listOf("city-weather", "current-weather", "hourly-forecast", "weather-advice")),
                message = "The screen must run four independently recorded machines",
            )
            assertTrue(retainedEntries.any { it.machine.name.startsWith("City Search") })
            assertTrue(retainedEntries.any { it.machine.name.startsWith("City Weather") })
            val parentEntry = retainedEntries.first { it.machine.stableId == "city-weather" && !it.snapshot.application.canResume() }
            assertFalse(actual = parentEntry.snapshot.application.canResume(), message = "A parent event still crossing routes is not a resumable cut")
            assertTrue(actual = retainedEntries.any { it.machine.stableId == "weather-advice" && it.snapshot.application.canResume() })
            val saved = stateKeeper.save()
            firstLifecycle.destroy()

            val lifecycle = LifecycleRegistry()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(
                        lifecycle,
                        instanceKeeper = keeper,
                        stateKeeper =
                            com.arkivanov.essenty.statekeeper
                                .StateKeeperDispatcher(saved),
                    ),
                    api,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    coroutineContext,
                )
            lifecycle.resume()
            assertSame(history, keeper.getOrCreate<WeatherHistory>(key = "weather-debug-history") { error("History must survive recreation") })
            assertTrue(
                history.timeline.entries.value
                    .containsAll(retainedEntries),
            )
            try {
                testScheduler.runCurrent()
                val weather =
                    kotlin.test.assertIs<actron.example.weather.feature.weather.presentation.CityWeatherComponent>(
                        root
                            .getChildStack()
                            .value.active.instance,
                    )
                assertEquals("Lisbon", weather.state.value.city.name)
                assertEquals(initialCalls + 2, requests.size)
                weather.onUiEvent(actron.example.weather.feature.weather.presentation.CityWeatherIntent.Back)
                testScheduler.runCurrent()
                val restoredSearch =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                assertEquals("Lis", restoredSearch.state.value.context.query)
                restoredSearch.onUiEvent(CitySearchIntent.SelectCity(demoCities[2]))
                restoredSearch.onUiEvent(CitySearchIntent.SelectCity(demoCities[2]))
                testScheduler.runCurrent()
                assertEquals(initialCalls + 4, requests.size)
                assertEquals(demoCities[2], requests.last())
                assertEquals(
                    1,
                    root
                        .getChildStack()
                        .value.backStack.size,
                )
            } finally {
                lifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun backReturnsToSameSearch_andRecreationRetainsQuery() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val back = BackDispatcher()
            val stateKeeper =
                com.arkivanov.essenty.statekeeper
                    .StateKeeperDispatcher()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle, instanceKeeper = keeper, backHandler = back, stateKeeper = stateKeeper),
                    SampleWeatherRepository,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(CitySearchIntent.QueryChanged("Lis"))
                testScheduler.runCurrent()
                assertEquals("Lis", search.state.value.context.query)
                search.onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
                testScheduler.runCurrent()
                kotlin.test.assertIs<actron.example.weather.feature.weather.presentation.CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                back.back()
                testScheduler.runCurrent()
                kotlin.test.assertIs<CitySearchComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                assertSame(
                    search,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                assertEquals("Lis", search.state.value.context.query)
                val saved = stateKeeper.save()
                lifecycle.destroy()

                val recreatedLifecycle = LifecycleRegistry()
                val recreated =
                    DebugWeatherRootComponent(
                        DefaultComponentContext(
                            recreatedLifecycle,
                            instanceKeeper = keeper,
                            stateKeeper =
                                com.arkivanov.essenty.statekeeper
                                    .StateKeeperDispatcher(saved),
                        ),
                        SampleWeatherRepository,
                        LocalCitySearchRepository,
                        SampleHourlyRepository,
                        coroutineContext,
                    )
                recreatedLifecycle.resume()
                testScheduler.runCurrent()
                assertEquals(
                    "Lis",
                    (
                        recreated
                            .getChildStack()
                            .value.active.instance as CitySearchComponent
                    ).state.value.context.query,
                )
                recreatedLifecycle.destroy()
            } finally {
                lifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun destroyingHostCancelsInFlightWeather() =
        runTest {
            var started = false
            var cancelled = false
            val api =
                WeatherRepository {
                    started = true
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val lifecycle = LifecycleRegistry()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle),
                    api,
                    LocalCitySearchRepository,
                    SampleHourlyRepository,
                    coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                (
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                ).onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
                testScheduler.runCurrent()
                assertTrue(started)
            } finally {
                lifecycle.destroy()
            }
            testScheduler.runCurrent()
            assertTrue(cancelled)
        }

    @Test
    fun poppingWeatherCancelsBothRequestsAndNextCityStartsFresh() =
        runTest {
            val current = mutableListOf<actron.example.weather.core.domain.City>()
            val hourly = mutableListOf<actron.example.weather.core.domain.City>()
            var cancelled = 0
            val lifecycle = LifecycleRegistry()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle),
                    WeatherRepository { city ->
                        current += city
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    LocalCitySearchRepository,
                    actron.example.weather.feature.weather.domain.HourlyForecastRepository { city ->
                        hourly += city
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    },
                    coroutineContext,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val search =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                search.onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
                testScheduler.runCurrent()
                val weather =
                    root
                        .getChildStack()
                        .value.active.instance as actron.example.weather.feature.weather.presentation.CityWeatherComponent
                weather.onUiEvent(actron.example.weather.feature.weather.presentation.CityWeatherIntent.Back)
                testScheduler.runCurrent()
                assertEquals(3, cancelled)
                assertSame(
                    search,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                search.onUiEvent(CitySearchIntent.SelectCity(demoCities[2]))
                testScheduler.runCurrent()
                assertEquals(listOf(demoCities[1], demoCities[1], demoCities[2], demoCities[2]), current)
                assertEquals(listOf(demoCities[1], demoCities[2]), hourly)
            } finally {
                lifecycle.destroy()
            }
            testScheduler.runCurrent()
            assertEquals(6, cancelled)
        }
}
