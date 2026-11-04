@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.example.weather

import actron.example.weather.app.WeatherTheme
import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.core.domain.City
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlin.test.Test

class WeatherAppTest {
    @Test
    fun graphFollowsSearchAndIndependentWeatherHistory() =
        runComposeUiTest {
            setRootContent(SampleWeatherRepository)
            onNodeWithTag("debug-settings").performClick()
            onNodeWithTag("graph-diagnostic-demo").assertDoesNotExist()
            onNodeWithTag("debug-panel-switch").performClick()
            onNodeWithTag("debug-settings-done").performClick()
            onNodeWithTag("city-search").performTextInput("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-graph-tab").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Idle", substring = true)
            onNodeWithTag("history-entry-3").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Debouncing", substring = true)
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("city-Lisbon").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("history-machine-filter").performClick()
            onNodeWithText("Current Weather").performClick()
            // The current panel deliberately keeps a pinned selection when its filter changes.
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Debouncing", substring = true)
            onNodeWithTag("history-navigation-menu").performClick()
            onNodeWithTag("history-last").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Success", substring = true)
            onNodeWithTag("graph-zoom-in").performClick()
            onNodeWithTag("graph-fit").performClick()
            onNodeWithTag("history-event-tab").performClick()
            onNodeWithTag("history-continue").performClick()
            onNodeWithTag("weather-refresh").assertIsEnabled()
        }

    @Test
    fun continueHereRestoresSearchAndAcceptsNewInput() =
        runComposeUiTest {
            setRootContent(SampleWeatherRepository)
            onNodeWithTag("city-search").performTextInput("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("debug-settings").performClick()
            onNodeWithTag("debug-panel-switch").performClick()
            onNodeWithTag("debug-settings-done").performClick()
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-continue").performClick()
            onNodeWithTag("city-search").assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text
                        .AnnotatedString(""),
                ),
            )
            onNodeWithTag("city-search").performTextReplacement("Tokyo")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Tokyo").fetchSemanticsNodes().isNotEmpty() }
        }

    @Test
    fun debounceDoesNotClaimRequestIsAlreadyRunning() =
        runComposeUiTest {
            val ui =
                androidx.compose.runtime.mutableStateOf(
                    actron.example.weather.feature.cities.presentation
                        .CitySearchState("P", actron.example.weather.core.mvi.RequestState.Debouncing)
                        .toUiState(true),
                )
            setContent {
                androidx.compose.material3.MaterialTheme {
                    actron.example.weather.feature.cities.presentation
                        .CitySearchScreen(ui.value, onIntent = {})
                }
            }
            onNodeWithTag("city-search-debounce").assertIsDisplayed()
            onNodeWithTag("city-search-loading").assertDoesNotExist()
            runOnIdle { ui.value = ui.value.copy(phase = actron.example.weather.core.mvi.RequestState.Loading) }
            onNodeWithTag("city-search-loading").assertIsDisplayed()
            onNodeWithTag("city-search-debounce").assertDoesNotExist()
        }

    @Test
    fun enablingPanelShowsEarlierSearchAndKeepsRecordingAfterClosing() =
        runComposeUiTest {
            setRootContent(SampleWeatherRepository)
            onNodeWithTag("city-search").performTextInput("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("weather-history-panel").assertDoesNotExist()
            onNodeWithTag("debug-settings").performClick()
            onNodeWithTag("debug-panel-switch").performClick()
            onNodeWithTag("debug-settings-done").performClick()
            onNodeWithTag("weather-history-panel").assertIsDisplayed()

            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("city-search").assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text
                        .AnnotatedString(""),
                ),
            )
            onNodeWithTag("city-search").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.IsEditable, false))
            onNodeWithTag("history-live").performClick()
            onNodeWithTag("city-search").assertTextContains("Lisbon")
            onNodeWithTag("city-search").assertIsEnabled()
            onNodeWithTag("debug-settings").performClick()
            onNodeWithTag("debug-panel-switch").performClick()
            onNodeWithTag("debug-settings-done").performClick()
            onNodeWithTag("city-search").performTextReplacement("Tokyo")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Tokyo").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("debug-settings").performClick()
            onNodeWithTag("debug-panel-switch").performClick()
            onNodeWithTag("debug-settings-done").performClick()
            onNodeWithTag("weather-history-panel").assertIsDisplayed()
        }

    @Test
    fun searchResult_opensWeatherForServerCoordinates() =
        runComposeUiTest {
            val city = City("Springfield", 39.78, -89.65)
            val requests = java.util.concurrent.CopyOnWriteArrayList<City>()
            val api =
                WeatherRepository { selected ->
                    requests += selected
                    WeatherReading(selected, 19.0, 18.0, 5.0, 0, "12:00")
                }
            val search = CitySearchRepository { listOf(CityOption(city, "Illinois, United States")) }
            setRootContent(api, citySearch = search)
            onNodeWithText("Weather, step by step").assertDoesNotExist()
            onNodeWithTag("city-search").performTextInput("Springfield")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Springfield").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            onNodeWithText("Illinois, United States").assertIsDisplayed()
            onNodeWithTag("city-Springfield").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text = "Springfield", substring = true).fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            kotlin.test.assertEquals(city, requests.last())
            onNodeWithTag("weather-back").performClick()
            onNodeWithTag("city-search").assertTextContains("Springfield")
            val previousCalls = requests.size
            onNodeWithTag("city-Springfield").assertIsEnabled().performClick()
            onNodeWithTag("weather-refresh").assertIsEnabled()
            waitUntil(timeoutMillis = 10_000) { requests.size == previousCalls + 2 }
            kotlin.test.assertEquals(city, requests.last())
            onNodeWithTag("weather-city").assertTextContains(value = "Springfield", substring = true)
        }

    @Test
    fun cityNavigation_supportsBack_anotherCity_andRefresh() =
        runComposeUiTest {
            val requests = java.util.concurrent.CopyOnWriteArrayList<City>()
            val api =
                object : WeatherRepository {
                    override suspend fun load(city: City): WeatherReading {
                        requests += city
                        return WeatherReading(city, 22.0, 21.0, 5.0, 0, "12:00")
                    }
                }
            setRootContent(api)
            onNodeWithTag("cities-screen").assertIsDisplayed()
            onNodeWithTag("weather-screen").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Lisbon").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text = "Lisbon", substring = true).fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("cities-screen").assertDoesNotExist()
            onNodeWithTag("weather-city").assertTextContains(value = "Lisbon", substring = true)
            onNodeWithTag("weather-back").performClick()
            onNodeWithTag("weather-screen").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-search-hint").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-search-hint").assertIsDisplayed()
            onNodeWithTag("city-Lisbon").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("Tokyo")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Tokyo").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Tokyo").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text = "Tokyo", substring = true).fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("weather-city").assertTextContains(value = "Tokyo", substring = true)
            val calls = requests.size
            onNodeWithTag("weather-refresh").performClick()
            waitUntil(timeoutMillis = 10_000) { requests.size == calls + 2 }
            kotlin.test.assertEquals(demoCities[2], requests.last())
            onNodeWithTag("weather-city").assertTextContains(value = "Tokyo", substring = true)
            onNodeWithTag("cities-screen").assertDoesNotExist()
        }

    @Test
    fun weatherAppShowsOnlyScreensWithoutDebugControls() =
        runComposeUiTest {
            setRootContent(SampleWeatherRepository)
            onNodeWithTag("cities-screen").assertIsDisplayed()
            assertNoDebugUi()
            onNodeWithTag("city-search").performTextReplacement("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Lisbon").performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(
                    if (java.util.Locale
                            .getDefault()
                            .language == "ru"
                    ) {
                        "Ощущается как 16° · ветер 12 км/ч"
                    } else {
                        "Feels like 16° · wind 12 km/h"
                    },
                ).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("weather-current").assertIsDisplayed()
            onNodeWithTag("hourly-forecast").assertIsDisplayed()
            assertNoDebugUi()
        }
}

private fun ComposeUiTest.assertNoDebugUi() {
    for (tag in listOf("time-travel-panel", "weather-capture", "weather-live", "weather-network-count", "article-story")) {
        onNodeWithTag(tag).assertDoesNotExist()
    }
    for (text in listOf("Time Travel", "Capture history", "Return to live")) {
        onNodeWithText(text).assertDoesNotExist()
    }
}

private fun ComposeUiTest.setRootContent(
    api: WeatherRepository,
    citySearch: CitySearchRepository = LocalCitySearchRepository,
) {
    val lifecycle = LifecycleRegistry()
    val root =
        runOnUiThread {
            DebugWeatherRootComponent(DefaultComponentContext(lifecycle), api, citySearch, SampleHourlyRepository)
        }
    setContent {
        DisposableEffect(root) {
            lifecycle.resume()
            onDispose { lifecycle.destroy() }
        }
        WeatherTheme {
            Box(Modifier.width(1180.dp).height(900.dp)) { root.Render() }
        }
    }
}
