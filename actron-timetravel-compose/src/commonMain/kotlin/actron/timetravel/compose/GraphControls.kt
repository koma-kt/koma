package actron.timetravel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun GraphControls(
    graph: HistoryGraph.Machine,
    viewport: GraphViewport,
    surface: GraphSurface,
    layout: GraphLayout,
    measurements: GraphMeasurements,
) {
    val legend =
        when (graph.definition.transitionStructure) {
            actron.statechart.TransitionStructure.Explicit -> HistoryText.CoverageLegend
            actron.statechart.TransitionStructure.Conservative -> HistoryText.PotentialTransitionsLegend
        }
    val zoomIn = historyText(HistoryText.ZoomIn)
    val zoomOut = historyText(HistoryText.ZoomOut)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        when (graph.coverage) {
            is HistoryGraphCoverage.Recorded -> {
                Text(
                    "${historyText(HistoryText.Coverage)}: ${measurements.covered} / ${graph.definition.transitions.size} · ${historyText(legend)}",
                    Modifier.weight(1f).testTag("graph-coverage"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HistoryGraphCoverage.Unavailable -> {
                when (graph.definition.transitionStructure) {
                    actron.statechart.TransitionStructure.Explicit -> Spacer(Modifier.weight(1f))
                    actron.statechart.TransitionStructure.Conservative -> Text(historyText(legend), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TextButton({ viewport.transform(surface.size.center, Offset.Zero, 1f / 1.25f) }, Modifier.width(40.dp).testTag("graph-zoom-out").semantics { contentDescription = zoomOut }, contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text("${(viewport.scale * 100).toInt()}%", Modifier.testTag("graph-scale"), style = MaterialTheme.typography.labelMedium)
        TextButton({ viewport.transform(surface.size.center, Offset.Zero, 1.25f) }, Modifier.width(40.dp).testTag("graph-zoom-in").semantics { contentDescription = zoomIn }, contentPadding = PaddingValues(0.dp)) { Text("+") }
        TextButton({ viewport.fit(surface.size, layout.size) }, Modifier.testTag("graph-fit"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(historyText(HistoryText.Fit)) }
    }
}
