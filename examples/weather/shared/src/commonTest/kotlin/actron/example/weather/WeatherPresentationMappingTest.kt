package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchControls
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.CitySearchUiCommand
import actron.example.weather.feature.cities.presentation.toCommand
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.CityWeatherIntent
import actron.example.weather.feature.weather.presentation.CityWeatherUiCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weather.presentation.WeatherUpdateUiModel
import actron.example.weather.feature.weather.presentation.toCommand
import actron.example.weather.feature.weather.presentation.toUiState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.resources.Res
import actron.example.weather.resources.debouncing
import actron.example.weather.resources.enter_city
import actron.example.weather.resources.no_cities
import actron.example.weather.resources.search_failed
import actron.example.weather.resources.searching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WeatherPresentationMappingTest {
    @Test
    fun searchProjectionOmitsInternalTransitionsAndRevision() {
        val initial = CitySearchState.Idle().toUiState()
        assertEquals(
            Res.string.enter_city,
            initial.messages
                .single()
                .text.resource,
        )
        assertEquals("city-search-hint", initial.messages.single().testTag)
        assertTrue(
            CitySearchState
                .QueryChanged("Paris", 1)
                .toUiState()
                .messages
                .isEmpty(),
        )
        val debounce = CitySearchState.Debouncing("Paris", 1).toUiState()
        assertEquals(
            Res.string.debouncing,
            debounce.messages
                .single()
                .text.resource,
        )
        assertEquals("city-search-debounce", debounce.messages.single().testTag)
        assertEquals(debounce, CitySearchState.Debouncing("Paris", 99).toUiState())
        val loading = CitySearchState.Loading("Paris", 1).toUiState()
        assertEquals(
            Res.string.searching,
            loading.messages
                .single()
                .text.resource,
        )
        assertEquals("city-search-loading", loading.messages.single().testTag)
        assertEquals(
            Res.string.no_cities,
            CitySearchState
                .Success("Paris", 1, emptyList())
                .toUiState()
                .messages
                .single()
                .text.resource,
        )
    }

    @Test
    fun searchErrorExposesLocalizedCopyAndRetryWithoutTechnicalFailure() {
        val first = CitySearchState.Error("Paris", 1, RequestFailure.Unavailable("secret server detail")).toUiState()
        val second = CitySearchState.Error("Paris", 2, RequestFailure.Unavailable("different detail")).toUiState()
        assertEquals(first, second)
        assertEquals(Res.string.search_failed, first.errors.single().resource)
        assertEquals(listOf(CitySearchIntent.Retry), first.actions)
        assertTrue(first.messages.isEmpty())
    }

    @Test
    fun resultProjectionPreservesSelectionCoordinatesAndOmitsBlankRegion() {
        val city = City("Paris", 33.66, -95.55)
        val model = CitySearchState.Success("Paris", 3, listOf(CityOption(city), CityOption(city, "Texas, United States"))).toUiState(CitySearchControls.ReadOnly)
        assertEquals(CitySearchControls.ReadOnly, model.controls)
        assertTrue(model.messages.isEmpty())
        assertEquals("Paris", model.results.first().name)
        assertEquals("city-Paris", model.results.first().testTag)
        assertTrue(
            model.results
                .first()
                .details
                .isEmpty(),
        )
        assertEquals(listOf("Texas, United States"), model.results.last().details)
        assertEquals(CitySearchIntent.SelectCity(city), model.results.last().select)
        assertEquals(
            CitySearchUiCommand.Publish(CitySearchComponent.News.CitySelected(city)),
            model.results
                .last()
                .select
                .toCommand(CitySearchState.Idle()),
        )
    }

    @Test
    fun searchIntentMappingUsesCurrentQueryForRetryAndKeepsInputUntrimmed() {
        val state = CitySearchState.Debouncing(" Paris ", 5)
        assertEquals(CitySearchUiCommand.Dispatch(CitySearchAction.ChangeQuery(" Tokyo ")), CitySearchIntent.QueryChanged(" Tokyo ").toCommand(state))
        assertEquals(CitySearchUiCommand.Dispatch(CitySearchAction.ChangeQuery(" Paris ")), CitySearchIntent.Retry.toCommand(state))
    }

    @Test
    fun weatherProjectionPreparesForecastAndTimestampBeforeRendering() {
        val city = City("Paris", 48.86, 2.35)
        val reading = WeatherReading(city, 10.6, 8.2, 3.8, 0, "2026-11-07T12:00", "2026-11-07T12:01:02Z")
        val context =
            WeatherContext(
                city = city,
                current = RequestState.Success(reading),
                hourly = RequestState.Success(HourlyForecast(city, listOf(ForecastHour("2026-11-07T13:00", -1.7, 4.8, 25)))),
            )
        val ui = context.toUiState(enabled = true)
        assertEquals(listOf(11), ui.temperature.arguments)
        assertEquals(listOf(8, 4), ui.detail.arguments)
        assertEquals(listOf(WeatherUpdateUiModel.Timestamp(Instant.parse("2026-11-07T12:01:02Z"))), ui.updatedAt)
        val hour = ui.forecast.single()
        assertEquals("13:00", hour.time)
        assertEquals(listOf(-2), hour.temperature.arguments)
        assertEquals(listOf(5), hour.wind.arguments)
        assertEquals(listOf(25), hour.rain.arguments)
        val recorded = context.copy(current = RequestState.Success(reading.copy(receivedAt = "", time = "2026-11-07T12:00"))).toUiState(enabled = false)
        assertEquals(listOf(WeatherUpdateUiModel.Literal("2026-11-07 12:00")), recorded.updatedAt)
        assertTrue(WeatherContext(city = city).toUiState(enabled = false).updatedAt.isEmpty())
    }

    @Test
    fun weatherIntentsTargetTheirOwningMachineOrNavigation() {
        assertEquals(CityWeatherUiCommand.Screen(WeatherScreenAction.Refresh), CityWeatherIntent.Refresh.toCommand())
        assertEquals(CityWeatherUiCommand.Forecast(HourlyForecastAction.Retry), CityWeatherIntent.RetryForecast.toCommand())
        assertEquals(CityWeatherUiCommand.Advice(WeatherAdviceAction.Retry), CityWeatherIntent.RetryAdvice.toCommand())
        assertEquals(CityWeatherUiCommand.Advice(WeatherAdviceAction.Dismiss), CityWeatherIntent.DismissAdvice.toCommand())
        assertEquals(CityWeatherUiCommand.Publish(CityWeatherComponent.News.BackClicked), CityWeatherIntent.Back.toCommand())
    }
}
