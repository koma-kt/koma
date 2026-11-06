package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RequestFailureLifecycleTest {
    @Test
    fun searchTimeoutUsesTheSameRetryableErrorStateAsRequestFailure() =
        runTest(testBody = {
            CitySearchMachine.create().test(
                scope = this,
                initial = CitySearchState.Idle(),
                scenario = {
                    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
                    advanceTimeBy(duration = 350.milliseconds)
                    advanceTimeBy(duration = 8.seconds)
                    assertEquals(expected = RequestFailure.Timeout, actual = expectState<CitySearchState.Error>().cause)
                    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
                    expectState<CitySearchState.Debouncing>()
                    advanceTimeBy(duration = 350.milliseconds)
                    expectState<CitySearchState.Loading>()
                    completeCommand(result = { CitySearchAction.Failed(revision = it.revision, message = "offline") })
                    assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<CitySearchState.Error>().cause)
                },
            )
        })

    @Test
    fun forecastTimeoutAndRequestFailureUseTheSameRetryableErrorState() =
        runTest(testBody = {
            HourlyForecastMachine.create().test(scope = this, initial = HourlyForecastState.Ready(city = demoCities.first()), scenario = {
                dispatch(action = HourlyForecastAction.Refresh)
                advanceTimeBy(duration = 8.seconds)
                assertEquals(expected = RequestFailure.Timeout, actual = expectState<HourlyForecastState.Error>().cause)
                dispatch(action = HourlyForecastAction.Retry)
                completeCommand(result = { HourlyForecastAction.Failed(message = "offline") })
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<HourlyForecastState.Error>().cause)
                dispatch(action = HourlyForecastAction.Refresh)
                expectState<HourlyForecastState.Loading>()
                advanceTimeBy(duration = 8.seconds)
            })
        })
}
