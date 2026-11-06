package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.resources.Res
import actron.example.weather.resources.choose_city
import actron.example.weather.resources.choose_weather_city
import actron.example.weather.resources.unknown_temperature
import kotlin.time.Instant

/** Already formatted immutable data: Compose neither interprets weather nor reads live machines. */
data class CityWeatherUiState(
    val city: String = "",
    val temperature: WeatherText = WeatherText(resource = Res.string.unknown_temperature),
    val detail: WeatherText = WeatherText(resource = Res.string.choose_weather_city),
    val updatedAt: List<WeatherUpdateUiModel> = emptyList(),
    val forecast: List<ForecastHourUiModel> = emptyList(),
    val forecastStatus: List<WeatherText> = listOf(WeatherText(resource = Res.string.choose_city)),
    val forecastFailed: Boolean = false,
    val enabled: Boolean = false,
    val advice: List<WeatherText> = emptyList(),
    val adviceActions: List<WeatherAdviceControl> = emptyList(),
)

/** An error card offers both actions; these are rendered controls, not an availability flag. */
enum class WeatherAdviceControl { Dismiss, Retry }

sealed interface CityWeatherIntent {
    data object DismissAdvice : CityWeatherIntent

    data object RetryAdvice : CityWeatherIntent

    data object Back : CityWeatherIntent

    data object Refresh : CityWeatherIntent

    data object RetryForecast : CityWeatherIntent
}

/** Locale-independent display data; Compose only resolves text and platform date resources. */
data class ForecastHourUiModel(
    val time: String,
    val temperature: WeatherText,
    val wind: WeatherText,
    val rain: WeatherText,
)

sealed interface WeatherUpdateUiModel {
    data class Timestamp(
        val instant: Instant,
    ) : WeatherUpdateUiModel

    data class Literal(
        val text: String,
    ) : WeatherUpdateUiModel
}
