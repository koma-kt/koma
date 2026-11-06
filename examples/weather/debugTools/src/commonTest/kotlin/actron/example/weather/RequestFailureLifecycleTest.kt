package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class RequestFailureLifecycleTest {
    @Test
    fun searchCanRetryAfterRepositoryFailure() =
        runTest(testBody = {
            var attempts = 0
            val machine =
                CitySearchMachine.create {
                    if (++attempts == 1) error("offline")
                    emptyList()
                }
            machine.test(
                scope = this,
                initial = CitySearchState.Idle(),
                scenario = {
                    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
                    advanceTimeBy(duration = 350.milliseconds)
                    expectState<CitySearchState.Error>()
                    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
                    expectState<CitySearchState.Debouncing>()
                    advanceTimeBy(duration = 350.milliseconds)
                    expectState<CitySearchState.Success>()
                },
            )
        })

    @Test
    fun forecastRetryCanCompleteAfterRepositoryFailure() =
        runTest(testBody = {
            var attempts = 0
            val machine =
                HourlyForecastMachine.create { city ->
                    if (++attempts == 1) error("offline")
                    HourlyForecast(city, emptyList())
                }
            machine.test(scope = this, initial = HourlyForecastState.Ready(city = demoCities.first()), scenario = {
                dispatch(action = HourlyForecastAction.Refresh)
                assertEquals(expected = RequestFailure.Unavailable(message = "offline"), actual = expectState<HourlyForecastState.Error>().cause)
                dispatch(action = HourlyForecastAction.Retry)
                expectState<HourlyForecastState.Success>()
            })
        })
}
