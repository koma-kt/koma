package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.domain.City

sealed interface CitySearchIntent {
    data class QueryChanged(
        val query: String,
    ) : CitySearchIntent

    data object Retry : CitySearchIntent

    data class SelectCity(
        val city: City,
    ) : CitySearchIntent
}

enum class CitySearchControls(
    val enabled: Boolean,
) {
    Interactive(true),
    ReadOnly(false),
    ;

    val readOnly: Boolean = !enabled
}
