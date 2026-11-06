package actron.example.weather

import actron.example.weather.feature.cities.presentation.CitySearchControls
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.toUiState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CitySearchInputUiTest {
    @Test
    fun typingKeepsCursorAndSelectionWhileSearchStateLagsBehind() =
        runComposeUiTest {
            val search = mutableStateOf<CitySearchState>(CitySearchState.Idle())
            val intents = mutableListOf<CitySearchIntent>()
            setContent { CitySearchScreen(uiState = search.value.toUiState(), onIntent = { intents.add(it) }) }
            val field = onNodeWithTag("city-search")
            field.performTextInput("М")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("М")))
            assertEquals(TextRange(1), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            field.performTextInput("о")
            // An older search response must not overwrite the current edit buffer.
            runOnIdle { search.value = CitySearchState.Debouncing("М", revision = 1) }
            field.performTextInput("сква")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Москва")))
            assertEquals(TextRange(6), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            field.performTextInputSelection(TextRange(0, 3))
            field.performTextInput("Тес")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Тесква")))
            assertEquals(TextRange(3), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            runOnIdle { assertEquals(CitySearchIntent.QueryChanged("Тесква"), intents.last()) }
        }

    @Test
    fun readOnlyFieldFollowsRecordingSnapshots() =
        runComposeUiTest {
            val search = mutableStateOf<CitySearchState>(CitySearchState.Idle("Москва"))
            val intents = mutableListOf<CitySearchIntent>()
            setContent { CitySearchScreen(uiState = search.value.toUiState(CitySearchControls.ReadOnly), onIntent = { intents.add(it) }) }
            onNodeWithTag("city-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Москва")))
            runOnIdle { search.value = CitySearchState.Idle("Омск") }
            onNodeWithTag("city-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Омск")))
            runOnIdle { assertEquals(emptyList(), intents) }
        }
}
