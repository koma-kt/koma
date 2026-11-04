@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherProcessRestoreTest {
    @Test
    fun processDeathRestoresWeatherAndSearchAndRestartsOnlyPendingForecast() =
        runTest {
            var currentCalls = 0
            var forecastCalls = 0
            val current =
                WeatherRepository { city ->
                    currentCalls++
                    WeatherReading(city, 20.0, 19.0, 2.0, 0, "saved reading")
                }
            val forecast =
                HourlyForecastRepository {
                    forecastCalls++
                    awaitCancellation()
                }
            var keeper = InstanceKeeperDispatcher()
            var lifecycle = LifecycleRegistry()
            var state = StateKeeperDispatcher()

            fun create() =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle, instanceKeeper = keeper, stateKeeper = state),
                    current,
                    LocalCitySearchRepository,
                    forecast,
                    coroutineContext,
                )
            var root = create()
            lifecycle.resume()
            testScheduler.runCurrent()
            val search =
                assertIs<CitySearchComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            search.onUiEvent(CitySearchIntent.QueryChanged("Lis"))
            testScheduler.runCurrent()
            search.onUiEvent(CitySearchIntent.SelectCity(demoCities[1]))
            testScheduler.runCurrent()
            val oldHistory = keeper.getOrCreate<WeatherHistory>("weather-debug-history") { error("missing history") }
            val oldWeather =
                assertIs<CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                ).state.value
            assertIs<RequestState.Success<WeatherReading>>(oldWeather.current)
            assertEquals(RequestState.Loading, oldWeather.hourly)
            assertEquals(2, currentCalls)
            assertEquals(1, forecastCalls)
            repeat(2) {
                // A real serialization round trip, then destroy every retained instance.
                val bytes = Json.encodeToString(SerializableContainer.serializer(), state.save())
                assertTrue(bytes.length < 100_000, "Only the current checkpoint belongs in saved state")
                lifecycle.destroy()
                keeper.destroy()
                testScheduler.runCurrent()
                keeper = InstanceKeeperDispatcher()
                lifecycle = LifecycleRegistry()
                state = StateKeeperDispatcher(Json.decodeFromString(SerializableContainer.serializer(), bytes))
                root = create()
                lifecycle.resume()
                testScheduler.runCurrent()
                val history = keeper.getOrCreate<WeatherHistory>("weather-debug-history") { error("missing history") }
                assertNotSame(oldHistory, history)
                assertTrue(
                    history.timeline.entries.value
                        .none { entry -> entry.snapshot.application === oldHistory.session.frame },
                )
                val restored =
                    assertIs<CityWeatherComponent>(
                        root
                            .getChildStack()
                            .value.active.instance,
                    )
                assertEquals(oldWeather, restored.state.value)
                assertEquals("Lis", history.session.frame.search.snapshot.context.query)
                assertEquals(2, currentCalls, "Completed requests must not repeat")
                assertEquals(it + 2, forecastCalls, "Each new process restarts the pending request once")
            }
            val weather =
                assertIs<CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            weather.onUiEvent(CityWeatherIntent.Back)
            testScheduler.runCurrent()
            val restoredSearch =
                assertIs<CitySearchComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            assertEquals("Lis", restoredSearch.state.value.context.query)
            restoredSearch.onUiEvent(CitySearchIntent.QueryChanged("Tokyo"))
            testScheduler.runCurrent()
            assertEquals("Tokyo", restoredSearch.state.value.context.query)
            lifecycle.destroy()
            keeper.destroy()
        }

    @Test
    fun completedParallelRegionsSurviveProcessDeathWithoutNetworkReload() =
        runTest {
            var currentCalls = 0
            var forecastCalls = 0
            val current =
                WeatherRepository { city ->
                    currentCalls++
                    WeatherReading(city = city, temperature = 10.0, feelsLike = 9.0, wind = 1.0, code = 61, time = "saved")
                }
            val forecast =
                HourlyForecastRepository { city ->
                    forecastCalls++
                    HourlyForecast(city, listOf(ForecastHour("12:00", 10.0, 1.0, 0)))
                }
            val keeper = InstanceKeeperDispatcher()
            val lifecycle = LifecycleRegistry()
            val state = StateKeeperDispatcher()
            val root = DebugWeatherRootComponent(DefaultComponentContext(lifecycle, instanceKeeper = keeper, stateKeeper = state), current, LocalCitySearchRepository, forecast, coroutineContext)
            lifecycle.resume()
            testScheduler.runCurrent()
            assertIs<CitySearchComponent>(
                root
                    .getChildStack()
                    .value.active.instance,
            ).onUiEvent(CitySearchIntent.SelectCity(demoCities[0]))
            testScheduler.runCurrent()
            val weather =
                assertIs<CityWeatherComponent>(
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            assertIs<WeatherAdvicePhase.Visible>(weather.state.value.advice)
            weather.onUiEvent(event = CityWeatherIntent.DismissAdvice)
            testScheduler.runCurrent()
            val expected = weather.state.value
            assertEquals(expected = WeatherAdvicePhase.Dismissed, actual = expected.advice)
            val bytes = Json.encodeToString(SerializableContainer.serializer(), state.save())
            lifecycle.destroy()
            keeper.destroy()
            testScheduler.runCurrent()
            val newKeeper = InstanceKeeperDispatcher()
            val newLifecycle = LifecycleRegistry()
            val restored = DebugWeatherRootComponent(DefaultComponentContext(newLifecycle, instanceKeeper = newKeeper, stateKeeper = StateKeeperDispatcher(Json.decodeFromString(SerializableContainer.serializer(), bytes))), current, LocalCitySearchRepository, forecast, coroutineContext)
            newLifecycle.resume()
            testScheduler.runCurrent()
            assertEquals(
                expected,
                assertIs<CityWeatherComponent>(
                    restored
                        .getChildStack()
                        .value.active.instance,
                ).state.value,
            )
            assertEquals(2, currentCalls)
            assertEquals(1, forecastCalls)
            newLifecycle.destroy()
            newKeeper.destroy()
        }
}
