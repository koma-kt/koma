@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.toUiState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.Test

class WeatherLocalizationTest {
    @Test
    fun theSameSnapshotsResolveNativeResourcesForRussianEnglishAndFallback() {
        val city = City(name = "Tokyo", latitude = 35.68, longitude = 139.69)
        val weather =
            WeatherContext(
                city = city,
                advice = WeatherAdvicePhase.Visible(items = listOf(WeatherAdvice.Umbrella)),
                current = RequestState.Success(value = WeatherReading(city = city, temperature = 18.0, feelsLike = 16.0, wind = 12.0, code = 0, time = "12:00")),
                hourly = RequestState.Success(value = HourlyForecast(city = city, hours = listOf(ForecastHour(time = "12:00", temperature = 18.0, wind = 12.0, rainChance = 25)))),
            ).toUiState(enabled = true)
        val search = CitySearchState(phase = RequestState.Error(cause = RequestFailure.Timeout)).toUiState(enabled = true)
        val original: Locale = Locale.getDefault()
        try {
            for (expectation in listOf(
                LocaleExpectation(language = "ru-RU", header = "Погода — Tokyo", advice = "Идёт дождь — возьмите зонт.", detail = "Ощущается как 16° · ветер 12 км/ч", rain = "Вероятность осадков 25%", failure = "Не удалось загрузить города. Попробуйте ещё раз."),
                LocaleExpectation(language = "en-US", header = "Weather — Tokyo", advice = "It is raining — take an umbrella.", detail = "Feels like 16° · wind 12 km/h", rain = "Rain 25%", failure = "Could not load cities. Try again."),
                LocaleExpectation(language = "ja-JP", header = "Weather — Tokyo", advice = "It is raining — take an umbrella.", detail = "Feels like 16° · wind 12 km/h", rain = "Rain 25%", failure = "Could not load cities. Try again."),
            )) {
                Locale.setDefault(Locale.forLanguageTag(expectation.language))
                runComposeUiTest {
                    setContent { MaterialTheme { CityWeatherScreen(state = weather, onIntent = {}) } }
                    onNodeWithText(text = expectation.header).assertExists()
                    onNodeWithText(text = expectation.advice).assertExists()
                    onNodeWithText(text = expectation.detail).assertExists()
                    onNodeWithText(text = expectation.rain).assertExists()
                }
                runComposeUiTest {
                    setContent { MaterialTheme { CitySearchScreen(state = search, onIntent = {}) } }
                    onNodeWithText(text = expectation.failure).assertExists()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}

private data class LocaleExpectation(
    val language: String,
    val header: String,
    val advice: String,
    val detail: String,
    val rain: String,
    val failure: String,
)
