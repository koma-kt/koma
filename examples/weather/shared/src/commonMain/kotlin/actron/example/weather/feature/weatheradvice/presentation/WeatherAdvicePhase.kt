package actron.example.weather.feature.weatheradvice.presentation

import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import kotlinx.serialization.Serializable

/** This child owns its request, interpreted advice and dismissal, independently of the other cards. */
@Serializable
sealed interface WeatherAdvicePhase {
    @Serializable data object Ready : WeatherAdvicePhase

    @Serializable data object Loading : WeatherAdvicePhase

    @Serializable data object Hidden : WeatherAdvicePhase

    @Serializable data class Visible(
        val items: List<WeatherAdvice>,
    ) : WeatherAdvicePhase

    @Serializable data object Dismissed : WeatherAdvicePhase

    @Serializable data class Error(
        val cause: RequestFailure,
    ) : WeatherAdvicePhase
}
