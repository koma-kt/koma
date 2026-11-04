package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.action
import actron.timetravel.compose.resources.active_states
import actron.timetravel.compose.resources.add_bookmark
import actron.timetravel.compose.resources.after
import actron.timetravel.compose.resources.all_fields
import actron.timetravel.compose.resources.all_machines
import actron.timetravel.compose.resources.app_live
import actron.timetravel.compose.resources.app_preview
import actron.timetravel.compose.resources.before
import actron.timetravel.compose.resources.bookmarks
import actron.timetravel.compose.resources.capacity
import actron.timetravel.compose.resources.changed_fields
import actron.timetravel.compose.resources.changes
import actron.timetravel.compose.resources.clear_comparison
import actron.timetravel.compose.resources.compact_value
import actron.timetravel.compose.resources.comparison_diagram_hint
import actron.timetravel.compose.resources.comparison_different_machine
import actron.timetravel.compose.resources.`continue`
import actron.timetravel.compose.resources.continue_hint
import actron.timetravel.compose.resources.continue_ready
import actron.timetravel.compose.resources.coverage
import actron.timetravel.compose.resources.coverage_legend
import actron.timetravel.compose.resources.declared_transition
import actron.timetravel.compose.resources.effect
import actron.timetravel.compose.resources.empty
import actron.timetravel.compose.resources.event
import actron.timetravel.compose.resources.events
import actron.timetravel.compose.resources.executions
import actron.timetravel.compose.resources.explore
import actron.timetravel.compose.resources.field
import actron.timetravel.compose.resources.field_search_window
import actron.timetravel.compose.resources.first_event
import actron.timetravel.compose.resources.fit
import actron.timetravel.compose.resources.from_state
import actron.timetravel.compose.resources.full_state
import actron.timetravel.compose.resources.full_value
import actron.timetravel.compose.resources.graph
import actron.timetravel.compose.resources.graph_hint
import actron.timetravel.compose.resources.guard
import actron.timetravel.compose.resources.hidden_selection
import actron.timetravel.compose.resources.hide_event_list
import actron.timetravel.compose.resources.hide_payload
import actron.timetravel.compose.resources.history
import actron.timetravel.compose.resources.imported_recording
import actron.timetravel.compose.resources.in_history
import actron.timetravel.compose.resources.last_event
import actron.timetravel.compose.resources.live
import actron.timetravel.compose.resources.milliseconds
import actron.timetravel.compose.resources.more
import actron.timetravel.compose.resources.more_fields
import actron.timetravel.compose.resources.navigation_back
import actron.timetravel.compose.resources.navigation_forward
import actron.timetravel.compose.resources.next_change
import actron.timetravel.compose.resources.next_event
import actron.timetravel.compose.resources.no_fields
import actron.timetravel.compose.resources.no_graph
import actron.timetravel.compose.resources.no_matches
import actron.timetravel.compose.resources.no_other_field_changes
import actron.timetravel.compose.resources.no_state_change
import actron.timetravel.compose.resources.outside_window
import actron.timetravel.compose.resources.pin_comparison
import actron.timetravel.compose.resources.pin_field
import actron.timetravel.compose.resources.pinned_field
import actron.timetravel.compose.resources.potential_transitions_legend
import actron.timetravel.compose.resources.previous_change
import actron.timetravel.compose.resources.previous_event
import actron.timetravel.compose.resources.previous_instance
import actron.timetravel.compose.resources.recorded_at
import actron.timetravel.compose.resources.remove_bookmark
import actron.timetravel.compose.resources.resize
import actron.timetravel.compose.resources.reveal
import actron.timetravel.compose.resources.search
import actron.timetravel.compose.resources.search_fields
import actron.timetravel.compose.resources.select_hint
import actron.timetravel.compose.resources.select_to_view
import actron.timetravel.compose.resources.session
import actron.timetravel.compose.resources.show_event_list
import actron.timetravel.compose.resources.show_payload
import actron.timetravel.compose.resources.state
import actron.timetravel.compose.resources.state_after
import actron.timetravel.compose.resources.state_before
import actron.timetravel.compose.resources.timeline
import actron.timetravel.compose.resources.title
import actron.timetravel.compose.resources.to_state
import actron.timetravel.compose.resources.transition
import actron.timetravel.compose.resources.transition_kind
import actron.timetravel.compose.resources.unchanged
import actron.timetravel.compose.resources.unsafe
import actron.timetravel.compose.resources.zoom_in
import actron.timetravel.compose.resources.zoom_out
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class HistoryText(
    val resource: StringResource,
) {
    Title(Res.string.title),
    ImportedRecording(Res.string.imported_recording),
    Session(Res.string.session),
    AppLive(Res.string.app_live),
    AppPreview(Res.string.app_preview),
    Search(Res.string.search),
    ShowEventList(Res.string.show_event_list),
    HideEventList(Res.string.hide_event_list),
    NoMatches(Res.string.no_matches),
    More(Res.string.more),
    Bookmarks(Res.string.bookmarks),
    Capacity(Res.string.capacity),
    AddBookmark(Res.string.add_bookmark),
    RemoveBookmark(Res.string.remove_bookmark),
    FirstEvent(Res.string.first_event),
    LastEvent(Res.string.last_event),
    NavigationBack(Res.string.navigation_back),
    NavigationForward(Res.string.navigation_forward),
    HiddenSelection(Res.string.hidden_selection),
    OutsideWindow(Res.string.outside_window),
    Reveal(Res.string.reveal),
    PinComparison(Res.string.pin_comparison),
    ClearComparison(Res.string.clear_comparison),
    ComparisonDifferentMachine(Res.string.comparison_different_machine),
    ComparisonDiagramHint(Res.string.comparison_diagram_hint),
    SearchFields(Res.string.search_fields),
    PinField(Res.string.pin_field),
    PinnedField(Res.string.pinned_field),
    AllFields(Res.string.all_fields),
    PreviousChange(Res.string.previous_change),
    NextChange(Res.string.next_change),
    FieldSearchWindow(Res.string.field_search_window),
    NoFields(Res.string.no_fields),
    FullValue(Res.string.full_value),
    CompactValue(Res.string.compact_value),
    AllMachines(Res.string.all_machines),
    PreviousInstance(Res.string.previous_instance),
    Timeline(Res.string.timeline),
    Events(Res.string.events),
    InHistory(Res.string.in_history),
    Empty(Res.string.empty),
    Explore(Res.string.explore),
    SelectHint(Res.string.select_hint),
    Event(Res.string.event),
    Graph(Res.string.graph),
    Coverage(Res.string.coverage),
    CoverageLegend(Res.string.coverage_legend),
    PotentialTransitionsLegend(Res.string.potential_transitions_legend),
    Executions(Res.string.executions),
    Transition(Res.string.transition),
    State(Res.string.state),
    FromState(Res.string.from_state),
    ToState(Res.string.to_state),
    Guard(Res.string.guard),
    Effect(Res.string.effect),
    TransitionKind(Res.string.transition_kind),
    DeclaredTransition(Res.string.declared_transition),
    NoGraph(Res.string.no_graph),
    Fit(Res.string.fit),
    ZoomIn(Res.string.zoom_in),
    ZoomOut(Res.string.zoom_out),
    GraphHint(Res.string.graph_hint),
    ActiveStates(Res.string.active_states),
    Changes(Res.string.changes),
    ChangedFields(Res.string.changed_fields),
    MoreFields(Res.string.more_fields),
    NoOtherFieldChanges(Res.string.no_other_field_changes),
    RecordedAt(Res.string.recorded_at),
    NoStateChange(Res.string.no_state_change),
    FullState(Res.string.full_state),
    Field(Res.string.field),
    Before(Res.string.before),
    After(Res.string.after),
    Unchanged(Res.string.unchanged),
    Continue(Res.string.`continue`),
    ContinueReady(Res.string.continue_ready),
    Unsafe(Res.string.unsafe),
    ContinueHint(Res.string.continue_hint),
    SelectToView(Res.string.select_to_view),
    History(Res.string.history),
    Live(Res.string.live),
    Action(Res.string.action),
    ShowPayload(Res.string.show_payload),
    HidePayload(Res.string.hide_payload),
    PreviousEvent(Res.string.previous_event),
    NextEvent(Res.string.next_event),
    StateBefore(Res.string.state_before),
    StateAfter(Res.string.state_after),
    Resize(Res.string.resize),
    Milliseconds(Res.string.milliseconds),
}

@Composable
internal fun historyText(text: HistoryText): String = stringResource(resource = text.resource)
