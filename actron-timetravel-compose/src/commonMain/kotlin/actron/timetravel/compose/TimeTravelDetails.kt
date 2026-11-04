@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.RecordedStep
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.input_failed
import actron.timetravel.compose.resources.input_ignored
import actron.timetravel.compose.resources.no_inputs
import actron.timetravel.compose.resources.snapshot_commands_timers
import actron.timetravel.compose.resources.snapshot_revision
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PanelRecorded(
    step: RecordedStep<*, *, *, *>,
    before: MachineSnapshot<*>,
    after: MachineSnapshot<*>,
) {
    PanelLines(listOf("Input: ${step.input}", "Recorded before: ${before.context}", "Recorded after: ${after.context}"))
    when (step) {
        is RecordedStep.Committed<*, *, *, *> -> PanelLines(listOf("Transitions: ${step.decision.transitions}", "Commands: ${step.decision.commands}", "Timers: ${step.decision.timersScheduled}", "Events: ${step.decision.effects}"))
        is RecordedStep.Ignored<*, *, *, *> -> Text(stringResource(resource = Res.string.input_ignored, step.reason))
        is RecordedStep.Failed<*, *, *, *> -> PanelProblem(stringResource(resource = Res.string.input_failed, step.failure))
    }
}

@Composable
internal fun PanelSnapshot(
    store: StoreInstanceId,
    snapshot: MachineSnapshot<*>,
) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("panel-selected-snapshot")) {
        Text(stringResource(resource = Res.string.snapshot_revision, store.value, snapshot.revision), style = MaterialTheme.typography.titleSmall)
        Text("${snapshot.configuration.active.names()} · ${snapshot.context}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(stringResource(resource = Res.string.snapshot_commands_timers, snapshot.commands.keys, snapshot.timers.keys), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PanelProblem(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("panel-problem"))
}

@Composable
internal fun PanelVerdict(message: String) = Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("panel-verdict"))

@Composable
internal fun PanelEmpty() = Text(stringResource(resource = Res.string.no_inputs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("panel-empty"))

@Composable
internal fun PanelLines(lines: List<String>) {
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
}

internal fun Set<actron.statechart.StateId>.names(): String = map { it.value }.sorted().joinToString(", ")

internal fun MachineInput<*>.panelLabel(): String =
    when (this) {
        is MachineInput.EntryResult -> "onEnter"
        is MachineInput.Start -> "Start"
        is MachineInput.Dispatch -> action.displayName(fallback = "Action")
        is MachineInput.CommandResult -> "Answer · ${action.displayName(fallback = "Result")}"
        is MachineInput.CommandCompleted -> "Command completed"
        is MachineInput.CommandFailed -> "Command failed"
        is MachineInput.CommandAbandoned -> "Command abandoned"
        is MachineInput.TimerFired -> "Timer fired"
        is MachineInput.BridgeReceived -> "Bridge · ${action.displayName(fallback = "Message")}"
        is MachineInput.External -> "Source · ${action.displayName(fallback = "Data")}"
    }

private fun actron.core.Action.displayName(fallback: String): String = this::class.simpleName ?: fallback
