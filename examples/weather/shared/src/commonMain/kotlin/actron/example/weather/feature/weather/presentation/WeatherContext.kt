package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import kotlinx.serialization.Serializable

/** Immutable screen projection of four independently owned machine snapshots. */
@Serializable
data class WeatherContext(
    val city: City,
    val current: RequestState<WeatherReading> = RequestState.Ready,
    val hourly: RequestState<HourlyForecast> = RequestState.Ready,
    val advice: WeatherAdvicePhase = WeatherAdvicePhase.Ready,
)
