package actron.example.weather.app.debug

import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.debug.resources.Res
import actron.example.weather.debug.resources.debug_panel
import actron.example.weather.debug.resources.done
import actron.example.weather.debug.resources.pending_completions
import actron.example.weather.debug.resources.pending_effects
import actron.example.weather.debug.resources.restart_requests
import actron.example.weather.debug.resources.retention
import actron.example.weather.debug.resources.settings
import actron.example.weather.feature.cities.presentation.CitySearchControls
import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.toUiState
import actron.timetravel.compose.HistoryContinuation
import actron.timetravel.compose.HistoryPanel
import actron.timetravel.compose.HistoryRecordingPanel
import actron.timetravel.compose.ResizableHistoryLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource

@Composable
fun WeatherDebugLayout(
    history: WeatherHistory,
    onContinue: (WeatherHistoryFrame) -> Unit,
    files: HistoryFiles = rememberHistoryFiles(),
    settings: WeatherSettings = remember { WeatherSettings() },
    viewer: WeatherRecordingViewer = remember { WeatherRecordingViewer() },
    content: @Composable () -> Unit,
) {
    val visible by history.panelVisible.collectAsState()
    Box(Modifier.fillMaxSize()) {
        val preview: @Composable () -> Unit = {
            val selection = history.panel.preview
            if (viewer.imported.isNotEmpty()) {
                WeatherRecordingScreen(viewer.imported.single(), onSettings = settings::open)
            } else if (selection.isEmpty()) {
                content()
            } else {
                val frame = selection.single().snapshot.application
                when (val route = frame.route) {
                    WeatherRoute.Search -> {
                        CitySearchScreen(
                            frame.search.snapshot.context,
                            controls = CitySearchControls.ReadOnly,
                            onIntent = {},
                            settingsAction = { WeatherSettingsButton(settings::open) },
                        )
                    }

                    is WeatherRoute.Weather -> {
                        CityWeatherScreen(
                            route.checkpoint
                                .context()
                                .toUiState(enabled = false),
                            onIntent = {},
                        )
                    }
                }
            }
        }
        if (visible) {
            ResizableHistoryLayout(
                history.panelLayout,
                Modifier.fillMaxSize(),
                content = preview,
                panel = {
                    Column(Modifier.fillMaxSize()) {
                        if (viewer.imported.isEmpty()) {
                            // The reusable panel delegates continuation/navigation to the host.
                            HistoryPanel(history.timeline, history.panel, { onContinue(it.snapshot) }, modifier = Modifier.weight(1f).testTag("weather-history-panel"), continuation = { it.application.continuation() }, graph = { history.graph(it) }, actions = { RecordingActions(viewer, files, history) })
                        } else {
                            val imported = viewer.imported.single()
                            // Display-only records have no executor checkpoint or continuation callback.
                            key(imported) { HistoryRecordingPanel(imported.recording, imported.panel, Modifier.weight(1f), WeatherRecording::graph, actions = { RecordingActions(viewer, files, history) }) }
                        }
                    }
                },
            )
        } else {
            preview()
        }
    }
    if (settings.isOpen) {
        AlertDialog(
            onDismissRequest = { settings.close() },
            title = { Text(stringResource(resource = Res.string.settings)) },
            text = {
                Column {
                    Row {
                        Text(stringResource(resource = Res.string.debug_panel), Modifier.weight(1f))
                        Switch(visible, {
                            history.setPanelVisible(it, viewer)
                        }, Modifier.testTag("debug-panel-switch"))
                    }
                    Text(stringResource(resource = Res.string.retention, history.capacity))
                }
            },
            confirmButton = { TextButton(onClick = { settings.close() }, modifier = Modifier.testTag("debug-settings-done")) { Text(stringResource(resource = Res.string.done)) } },
        )
    }
}

internal data class ContinuationWork(
    val endings: Int,
    val effects: Int,
)

/** The executor owns continuation eligibility; UI only supplies localized explanations. */
internal fun WeatherFrame.continuationWork(): ContinuationWork {
    val ending =
        search.ending.size +
            when (val screen = route) {
                WeatherRoute.Search -> 0
                is WeatherRoute.Weather -> listOf(screen.checkpoint.screen, screen.checkpoint.current, screen.checkpoint.hourly, screen.checkpoint.advice).sumOf { it.ending.size }
            }
    val effects =
        search.effects.size +
            when (val screen = route) {
                WeatherRoute.Search -> 0
                is WeatherRoute.Weather -> listOf(screen.checkpoint.screen, screen.checkpoint.current, screen.checkpoint.hourly, screen.checkpoint.advice).sumOf { it.effects.size } + screen.checkpoint.inFlight.size
            }
    return ContinuationWork(endings = ending, effects = effects)
}

@Composable
internal fun WeatherFrame.continuation(): HistoryContinuation {
    val work = continuationWork()
    if (work.endings + work.effects == 0) {
        return HistoryContinuation.Available(consequences = stringResource(resource = Res.string.restart_requests))
    }
    val reasons = mutableListOf<String>()
    if (work.endings > 0) reasons.add(stringResource(resource = Res.string.pending_completions, work.endings))
    if (work.effects > 0) reasons.add(stringResource(resource = Res.string.pending_effects, work.effects))
    return HistoryContinuation.Unavailable(reason = reasons.joinToString(separator = " "))
}

internal fun WeatherHistory.setPanelVisible(
    visible: Boolean,
    viewer: WeatherRecordingViewer,
) {
    panelVisible.value = visible
    if (!visible) {
        panel.returnToLive()
        viewer.close()
    }
}
