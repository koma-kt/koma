package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.mvi.RequestState
import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import actron.example.weather.resources.Res
import actron.example.weather.resources.advice_cold
import actron.example.weather.resources.advice_failed
import actron.example.weather.resources.advice_heat
import actron.example.weather.resources.advice_ice
import actron.example.weather.resources.advice_loading
import actron.example.weather.resources.advice_ready
import actron.example.weather.resources.advice_storm
import actron.example.weather.resources.advice_umbrella
import actron.example.weather.resources.advice_wind
import actron.example.weather.resources.choose_city
import actron.example.weather.resources.choose_weather_city
import actron.example.weather.resources.feels_like
import actron.example.weather.resources.forecast_failed
import actron.example.weather.resources.forecast_ready
import actron.example.weather.resources.loading_forecast
import actron.example.weather.resources.loading_weather
import actron.example.weather.resources.temperature
import actron.example.weather.resources.unknown_temperature
import actron.example.weather.resources.weather_failed
import actron.example.weather.resources.weather_ready

fun WeatherContext.toUiState(enabled: Boolean): CityWeatherUiState {
    val data = this
    var temperature = WeatherText(resource = Res.string.unknown_temperature)
    var updatedAt = ""
    val detail =
        when (val current = data.current) {
            is RequestState.Success -> {
                val reading = current.value
                temperature = WeatherText(resource = Res.string.temperature, arguments = listOf(kotlin.math.round(x = reading.temperature).toInt()))
                updatedAt =
                    if (reading.receivedAt.isNotEmpty()) {
                        reading.receivedAt
                    } else {
                        reading.time.replace('T', ' ')
                    }
                WeatherText(resource = Res.string.feels_like, arguments = listOf(kotlin.math.round(x = reading.feelsLike).toInt(), kotlin.math.round(x = reading.wind).toInt()))
            }

            RequestState.QueryChanged, RequestState.Debouncing, RequestState.Loading -> {
                WeatherText(resource = Res.string.loading_weather)
            }

            is RequestState.Error -> {
                WeatherText(resource = Res.string.weather_failed)
            }

            RequestState.Ready -> {
                WeatherText(resource = Res.string.weather_ready)
            }

            RequestState.Idle -> {
                WeatherText(resource = Res.string.choose_weather_city)
            }
        }
    val hourly = data.hourly
    val forecast = if (hourly is RequestState.Success) hourly.value.hours else emptyList()
    return CityWeatherUiState(
        city = city.name,
        temperature = temperature,
        detail = detail,
        updatedAt = updatedAt,
        forecast = forecast,
        forecastStatus = forecastStatus(state = hourly),
        forecastFailed = hourly is RequestState.Error,
        enabled = enabled,
        advice = adviceText(state = advice),
        adviceActions =
            when (advice) {
                WeatherAdvicePhase.Ready, WeatherAdvicePhase.Hidden, WeatherAdvicePhase.Dismissed -> emptyList()
                is WeatherAdvicePhase.Error -> listOf(WeatherAdviceControl.Retry, WeatherAdviceControl.Dismiss)
                WeatherAdvicePhase.Loading, is WeatherAdvicePhase.Visible -> listOf(WeatherAdviceControl.Dismiss)
            },
    )
}

private fun adviceText(state: WeatherAdvicePhase): List<WeatherText> =
    when (state) {
        WeatherAdvicePhase.Ready -> {
            listOf(WeatherText(resource = Res.string.advice_ready))
        }

        WeatherAdvicePhase.Loading -> {
            listOf(WeatherText(resource = Res.string.advice_loading))
        }

        WeatherAdvicePhase.Hidden, WeatherAdvicePhase.Dismissed -> {
            emptyList()
        }

        is WeatherAdvicePhase.Error -> {
            listOf(WeatherText(resource = Res.string.advice_failed))
        }

        is WeatherAdvicePhase.Visible -> {
            state.items.map(transform = { item ->
                WeatherText(
                    resource =
                        when (item) {
                            WeatherAdvice.Umbrella -> Res.string.advice_umbrella
                            WeatherAdvice.SlipperyRoads -> Res.string.advice_ice
                            WeatherAdvice.StrongWind -> Res.string.advice_wind
                            WeatherAdvice.Heat -> Res.string.advice_heat
                            WeatherAdvice.Cold -> Res.string.advice_cold
                            WeatherAdvice.Thunderstorm -> Res.string.advice_storm
                        },
                )
            })
        }
    }

/** The forecast region owns its status copy, separately from current-weather formatting. */
private fun forecastStatus(state: RequestState<HourlyForecast>): List<WeatherText> =
    when (state) {
        is RequestState.Success -> emptyList()
        RequestState.QueryChanged, RequestState.Debouncing, RequestState.Loading -> listOf(WeatherText(resource = Res.string.loading_forecast))
        is RequestState.Error -> listOf(WeatherText(resource = Res.string.forecast_failed))
        RequestState.Ready -> listOf(WeatherText(resource = Res.string.forecast_ready))
        RequestState.Idle -> listOf(WeatherText(resource = Res.string.choose_city))
    }
