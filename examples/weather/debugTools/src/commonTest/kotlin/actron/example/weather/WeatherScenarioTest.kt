package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class WeatherScenarioTest {
    @Test
    fun adviceHasItsOwnEmptyVisibleDismissedFailureRetryAndTimeoutCycle() =
        runTest {
            val city = demoCities.first()
            WeatherAdviceMachine.create().test(scope = this, initial = WeatherAdviceState.Ready(city = city)) {
                dispatch(action = WeatherAdviceAction.Refresh)
                completeCommand { WeatherAdviceAction.Loaded(city = city, items = emptyList()) }
                expectState<WeatherAdviceState.Hidden>()
                dispatch(action = WeatherAdviceAction.Refresh)
                completeCommand { WeatherAdviceAction.Loaded(city = city, items = listOf(WeatherAdvice.Umbrella)) }
                assertEquals(expected = listOf(WeatherAdvice.Umbrella), actual = expectState<WeatherAdviceState.Visible>().items)
                dispatch(action = WeatherAdviceAction.Dismiss)
                expectState<WeatherAdviceState.Dismissed>()
                dispatch(action = WeatherAdviceAction.Refresh)
                completeCommand { WeatherAdviceAction.Failed(message = "offline") }
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<WeatherAdviceState.Error>().cause)
                dispatch(action = WeatherAdviceAction.Retry)
                advanceTimeBy(duration = 8.seconds)
                assertEquals(expected = RequestFailure.Timeout, actual = expectState<WeatherAdviceState.Error>().cause)
                dispatch(action = WeatherAdviceAction.Dismiss)
                expectState<WeatherAdviceState.Dismissed>()
            }
        }

    @Test
    fun forecastCanBeExecutedAndRetriedWithoutAnyScreenOrCurrentWeatherMachine() =
        runTest(testBody = {
            val city = demoCities.first()
            HourlyForecastMachine.create().test(scope = this, initial = HourlyForecastState.Ready(city = city), scenario = {
                expectState<HourlyForecastState.Ready>()
                dispatch(action = HourlyForecastAction.Refresh)
                completeCommand(result = { HourlyForecastAction.Loaded(forecast = HourlyForecast(city = city, hours = emptyList())) })
                expectState<HourlyForecastState.Success>()
                dispatch(action = HourlyForecastAction.Refresh)
                completeCommand(result = { HourlyForecastAction.Failed(message = "offline") })
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<HourlyForecastState.Error>().cause)
                dispatch(action = HourlyForecastAction.Retry)
                advanceTimeBy(duration = 8.seconds)
                expectState<HourlyForecastState.Error>()
            })
        })
}
