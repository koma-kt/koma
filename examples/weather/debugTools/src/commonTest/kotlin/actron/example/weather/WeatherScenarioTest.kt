package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherScenarioTest {
    @Test
    fun adviceHasItsOwnEmptyVisibleDismissedFailureRetryCycle() =
        runTest {
            val city = demoCities.first()
            WeatherAdviceMachine.create { kotlinx.coroutines.awaitCancellation() }.test(scope = this, initial = WeatherAdviceState.Ready(city = city)) {
                dispatch(action = WeatherAdviceAction.Refresh)
                dispatch(WeatherAdviceAction.Loaded(city = city, items = emptyList()))
                expectState<WeatherAdviceState.Hidden>()
                dispatch(action = WeatherAdviceAction.Refresh)
                dispatch(WeatherAdviceAction.Loaded(city = city, items = listOf(WeatherAdvice.Umbrella)))
                assertEquals(expected = listOf(WeatherAdvice.Umbrella), actual = expectState<WeatherAdviceState.Visible>().items)
                dispatch(action = WeatherAdviceAction.Dismiss)
                expectState<WeatherAdviceState.Dismissed>()
                dispatch(action = WeatherAdviceAction.Refresh)
                dispatch(WeatherAdviceAction.Failed(message = "offline"))
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<WeatherAdviceState.Error>().cause)
                dispatch(action = WeatherAdviceAction.Retry)
                expectState<WeatherAdviceState.Loading>()
                dispatch(action = WeatherAdviceAction.Dismiss)
                expectState<WeatherAdviceState.Dismissed>()
            }
        }

    @Test
    fun forecastCanBeExecutedAndRetriedWithoutAnyScreenOrCurrentWeatherMachine() =
        runTest(testBody = {
            val city = demoCities.first()
            HourlyForecastMachine.create { kotlinx.coroutines.awaitCancellation() }.test(scope = this, initial = HourlyForecastState.Ready(city = city), scenario = {
                expectState<HourlyForecastState.Ready>()
                dispatch(action = HourlyForecastAction.Refresh)
                dispatch(HourlyForecastAction.Loaded(forecast = HourlyForecast(city = city, hours = emptyList())))
                expectState<HourlyForecastState.Success>()
                dispatch(action = HourlyForecastAction.Refresh)
                dispatch(HourlyForecastAction.Failed(message = "offline"))
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<HourlyForecastState.Error>().cause)
                dispatch(action = HourlyForecastAction.Retry)
                dispatch(HourlyForecastAction.Loaded(HourlyForecast(city, emptyList())))
                expectState<HourlyForecastState.Success>()
            })
        })
}
