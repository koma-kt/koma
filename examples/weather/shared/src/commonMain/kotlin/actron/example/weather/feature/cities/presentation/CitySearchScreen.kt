package actron.example.weather.feature.cities.presentation

import actron.example.weather.resources.Res
import actron.example.weather.resources.cities
import actron.example.weather.resources.debouncing
import actron.example.weather.resources.enter_city
import actron.example.weather.resources.no_cities
import actron.example.weather.resources.retry
import actron.example.weather.resources.search_cities
import actron.example.weather.resources.search_failed
import actron.example.weather.resources.searching
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CitySearchScreen(
    state: CitySearchState,
    controls: CitySearchControls = CitySearchControls.Interactive,
    modifier: Modifier = Modifier,
    onIntent: (CitySearchIntent) -> Unit,
    settingsAction: @Composable () -> Unit = {},
) {
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
                value = state.query,
                onValueChange = { onIntent(CitySearchIntent.QueryChanged(it)) },
                label = { Text(stringResource(resource = Res.string.search_cities)) },
                singleLine = true,
                readOnly = controls.readOnly,
                modifier = Modifier.fillMaxWidth().testTag("city-search"),
            )
            when (state) {
                is CitySearchState.QueryChanged -> {
                    // Automatic transitions settle before publication; there is no intermediate UI.
                }

                is CitySearchState.Idle -> {
                    Text(stringResource(resource = Res.string.enter_city), modifier = Modifier.testTag("city-search-hint"))
                }

                is CitySearchState.Debouncing -> {
                    Text(stringResource(resource = Res.string.debouncing), modifier = Modifier.testTag("city-search-debounce"))
                }

                is CitySearchState.Loading -> {
                    Text(stringResource(resource = Res.string.searching), modifier = Modifier.testTag("city-search-loading"))
                }

                is CitySearchState.Error -> {
                    Text(stringResource(resource = Res.string.search_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onIntent(CitySearchIntent.Retry) }, enabled = controls.enabled) { Text(stringResource(resource = Res.string.retry)) }
                }

                is CitySearchState.Success -> {
                    if (state.results.isEmpty()) Text(stringResource(resource = Res.string.no_cities))
                    for (option in state.results) {
                        val city = option.city
                        OutlinedButton(
                            onClick = { onIntent(CitySearchIntent.SelectCity(city)) },
                            enabled = controls.enabled,
                            colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth().testTag("city-${city.name}"),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(city.name, style = MaterialTheme.typography.titleMedium)
                                if (option.region.isNotBlank()) Text(option.region, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
