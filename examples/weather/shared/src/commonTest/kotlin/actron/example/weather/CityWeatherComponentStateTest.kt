@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.app.WeatherMachines
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.freshWeather
import actron.example.weather.core.component.ParentBridge
import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.CityWeatherIntent
import actron.example.weather.feature.weather.presentation.CityWeatherUiState
import actron.example.weather.feature.weather.presentation.DefaultCityWeatherComponent
import actron.example.weather.resources.Res
import actron.example.weather.resources.loading_forecast
import actron.example.weather.resources.loading_weather
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CityWeatherComponentStateTest {
    @Test
    fun componentProjectsParentAndAllChildMachinesForReadsAndCollectors() =
        runTest {
            val city = City("Paris", 48.86, 2.35)
            val current = CompletableDeferred<WeatherReading>()
            val forecast = CompletableDeferred<HourlyForecast>()
            val lifecycle = LifecycleRegistry()
            val machines =
                WeatherMachines(
                    session = WeatherSession(),
                    initial = freshWeather(city),
                    currentRepository = { current.await() },
                    hourlyRepository = { forecast.await() },
                    coroutineContext = coroutineContext,
                )
            val component: CityWeatherComponent =
                DefaultCityWeatherComponent(
                    componentContext = DefaultComponentContext(lifecycle),
                    endpoint = ParentBridge<Nothing, CityWeatherComponent.News>().endpoint,
                    coroutineContext = coroutineContext,
                    machines = machines,
                )
            try {
                val state: StateFlow<CityWeatherUiState> = component.state
                assertEquals("Paris", state.value.city)
                assertFalse(state.value.enabled)
                machines.start()
                runCurrent()
                assertTrue(state.value.enabled)
                assertEquals(Res.string.loading_weather, state.value.detail.resource)
                assertEquals(
                    Res.string.loading_forecast,
                    state.value.forecastStatus
                        .single()
                        .resource,
                )
                val values = mutableListOf<CityWeatherUiState>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        state.collect { values.add(it) }
                    }
                runCurrent()
                assertEquals(listOf(state.value), values)
                current.complete(WeatherReading(city, 10.7, 8.0, 2.0, 61, "12:00"))
                runCurrent()
                assertEquals(listOf(11), state.value.temperature.arguments)
                assertTrue(state.value.advice.isNotEmpty())
                assertEquals(state.value, values.last())
                forecast.complete(HourlyForecast(city, listOf(ForecastHour("2026-11-07T13:00", 12.2, 4.8, 25))))
                runCurrent()
                assertEquals(
                    "13:00",
                    state.value.forecast
                        .single()
                        .time,
                )
                assertEquals(
                    listOf(5),
                    state.value.forecast
                        .single()
                        .wind.arguments,
                )
                assertTrue(state.value.forecastStatus.isEmpty())
                assertEquals(state.value, values.last())
                assertEquals(listOf(state.value), state.replayCache)
                collector.cancel()
                runCurrent()
                component.onUiEvent(CityWeatherIntent.DismissAdvice)
                runCurrent()
                assertTrue(state.value.advice.isEmpty())
                assertTrue(values.last().advice.isNotEmpty(), "Cancelled UI collector must receive no more values")
            } finally {
                lifecycle.destroy()
                machines.onDestroy()
                runCurrent()
            }
        }
}
