package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.presentation.WeatherText

/** A display snapshot; machine phases, revisions and failures stay behind the mapper. */
data class CitySearchUiState(
    val query: String,
    val controls: CitySearchControls,
    val messages: List<CitySearchMessage>,
    val errors: List<WeatherText>,
    val actions: List<CitySearchIntent.Retry>,
    val results: List<CitySearchResultUiModel>,
)

data class CitySearchMessage(
    val text: WeatherText,
    val testTag: String,
)

data class CitySearchResultUiModel(
    val name: String,
    val details: List<String>,
    val testTag: String,
    val select: CitySearchIntent.SelectCity,
)
