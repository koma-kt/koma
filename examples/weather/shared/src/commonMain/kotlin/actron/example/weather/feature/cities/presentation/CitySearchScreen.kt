package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.presentation.resolve
import actron.example.weather.resources.Res
import actron.example.weather.resources.cities
import actron.example.weather.resources.retry
import actron.example.weather.resources.search_cities
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CitySearchScreen(
    uiState: CitySearchUiState,
    modifier: Modifier = Modifier,
    onIntent: (CitySearchIntent) -> Unit,
    settingsAction: @Composable () -> Unit = {},
) {
    // Editing (including selection and IME composition) belongs to the field. Search snapshots
    // arrive asynchronously and must not feed older text back into an active editing session.
    val controls = uiState.controls
    val queryField = rememberTextFieldState(initialText = uiState.query)
    val currentOnIntent by rememberUpdatedState(onIntent)
    LaunchedEffect(queryField, controls) {
        when (controls) {
            CitySearchControls.Interactive -> {
                snapshotFlow { queryField.text.toString() }
                    .drop(1)
                    .collect { currentOnIntent(CitySearchIntent.QueryChanged(it)) }
            }

            CitySearchControls.ReadOnly -> {
                Unit
            }
        }
    }
    LaunchedEffect(uiState.query, controls) {
        when (controls) {
            CitySearchControls.ReadOnly -> queryField.setTextAndPlaceCursorAtEnd(uiState.query)
            CitySearchControls.Interactive -> Unit
        }
    }
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("cities-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(resource = Res.string.cities), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                settingsAction()
            }
            OutlinedTextField(
                state = queryField,
                label = { Text(stringResource(resource = Res.string.search_cities)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                readOnly = controls.readOnly,
                modifier = Modifier.fillMaxWidth().testTag("city-search"),
            )
            for (message in uiState.messages) {
                Text(message.text.resolve(), modifier = Modifier.testTag(message.testTag))
            }
            for (error in uiState.errors) {
                Text(error.resolve(), color = MaterialTheme.colorScheme.error)
            }
            for (action in uiState.actions) {
                TextButton(onClick = { onIntent(action) }, enabled = controls.enabled) { Text(stringResource(resource = Res.string.retry)) }
            }
            for (result in uiState.results) {
                OutlinedButton(
                    onClick = { onIntent(result.select) },
                    enabled = controls.enabled,
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().testTag(result.testTag),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(result.name, style = MaterialTheme.typography.titleMedium)
                        for (detail in result.details) Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
