package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.presentation.CityWeatherIntent
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.toUiState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WeatherAdviceUiTest {
    private var originalLocale: Locale = Locale.getDefault()

    @BeforeTest
    fun selectEnglishResources() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun parentHeaderNamesCityAndAdviceCardEmitsItsOwnDismissIntent() =
        runComposeUiTest(block = {
            val intents = mutableListOf<CityWeatherIntent>()
            val context = WeatherContext(city = City(name = "London", latitude = 51.5, longitude = -0.1), advice = WeatherAdvicePhase.Visible(items = listOf(WeatherAdvice.Umbrella, WeatherAdvice.SlipperyRoads)))
            setContent(composable = { CityWeatherScreen(state = context.toUiState(enabled = true), onIntent = { intents.add(element = it) }) })
            onNodeWithTag(testTag = "weather-header").assertExists()
            onNodeWithTag(testTag = "weather-city").assertTextContains(value = "Weather — London")
            onNodeWithText(text = "It is raining — take an umbrella.").assertExists()
            onNodeWithText(text = "Freezing precipitation — roads may be slippery.").assertExists()
            onNodeWithTag(testTag = "advice-retry").assertDoesNotExist()
            onNodeWithTag(testTag = "advice-dismiss").performClick()
            assertEquals(expected = listOf<CityWeatherIntent>(CityWeatherIntent.DismissAdvice), actual = intents)
        })

    @Test
    fun failedAdviceOffersItsOwnRetryAndCanStillBeDismissed() =
        runComposeUiTest(block = {
            val intents = mutableListOf<CityWeatherIntent>()
            val context = WeatherContext(city = City(name = "London", latitude = 51.5, longitude = -0.1), advice = WeatherAdvicePhase.Error(cause = RequestFailure.Timeout))
            setContent(composable = { CityWeatherScreen(state = context.toUiState(enabled = true), onIntent = { intents.add(element = it) }) })
            onNodeWithTag(testTag = "advice-retry").performClick()
            onNodeWithTag(testTag = "advice-dismiss").performClick()
            assertEquals(expected = listOf(CityWeatherIntent.RetryAdvice, CityWeatherIntent.DismissAdvice), actual = intents)
        })
}
