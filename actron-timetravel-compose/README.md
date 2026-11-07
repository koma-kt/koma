# actron-timetravel-compose

The debug screen for recorded Machines: select a position, move every group member together,
compare decisions, or experiment in a branch. Choose `GroupTimeTravelControls` for the whole
replay → experiment → return workflow, `GroupReplayControls` for replay only, or
`ReplayControls` for one Store. Add this to a debug dependency graph; setup follows the
[installation guide](../README.md#installation).

## Application history panel

`HistoryPanel(timeline, state, onContinue)` displays a bounded, session-wide
`HistoryTimeline<S>` across machine instances. It provides a machine filter, chronological
inputs, expandable action payloads, before/after states and field changes.
The complete interactive graph and the graphical **state before → action → state after**
diagram share one event cursor. Previous/next buttons work in either tab; **More** also provides
first/last visible event and navigation back/forward. Alt+Up/Down moves through visible events;
Alt+Left/Right returns through inspection locations. Text editing keeps its own keyboard behavior.

While previewing history, the counter shows the selected event's persistent ID and the latest
recorded event ID, followed by the capacity limit (for example, “Event 999 / 1000 · limit 500”).
Live mode instead says “In history: 1000 events · limit 500”. IDs keep increasing after capacity
eviction; neither counter is renumbered by filters or reduced to the retained buffer size.
The collapsed narrow layout uses the same counter.

Clicking a visible row preserves the list's scroll position. Cursor buttons, keyboard shortcuts,
field navigation and an explicit reveal still bring the selected event into view.
Rows use the details diagram: outlined before/after states below the short action name,
with an upward arrow from the old state and a downward arrow to the new state.
Both the rows and the selected event omit active states shared by before and after. Changed
states keep their region-qualified names (for example, `Current.Loading` → `Current.Success`).
An identical nonempty configuration says “No state change”; missing states use `—`.
The full recorded configuration remains available among the event's state fields.
A neutral selected-row surface stays distinct from the action's accent container and themed border.
Rows then name at most two changed data fields, with bounded names and a count of further
changes, in at most two lines. When that list is empty, “No other field changes” explains it.
Unchanged fields and trace metadata (`revision`, `type`) stay out of the preview; the original
action payload and all state fields and values remain available in the event details and search.
“Recorded” labels the local recording timestamp; milliseconds appear as the fractional part of
the seconds, not as a separate duration.

Place the panel inside the application's `MaterialTheme`. Colors, status containers, control/card
shapes and graph typography follow that theme, including changes while the panel is open.
Technical payloads retain monospace text; no separate panel palette or theme provider is required.

Search matches action, source, outcome, machine name/ID and changed field paths/values. Terms
are combined; `#12` matches event 12 exactly. Search and machine/bookmark filters change only
the list: the selected snapshot and graph remain pinned. A banner identifies a hidden selection
or a snapshot outside the retained window. **Show in history** clears filters to reveal a retained
selection. Search covers only retained entries and the structured fields supplied by the host.

The small bookmark icon sits at the top right of the event's metadata line; the diagram below
uses the full row width. Its unchecked outline is muted; the filled icon uses the theme accent.
Click it to add or remove a bookmark without changing selection or preview. A filled icon marks a bookmarked event; **Bookmarks only** filters the list.
**More** can pin an event's state after the event as comparison **A**. Selecting
another event supplies **B**. A/B compares fields of the same logical machine; the graphical
transition above always describes the selected event's original before/action/after. A different
machine falls back to that event's own changes and displays an explanation. Clear A to return
to ordinary before/after comparisons. Each field has search, focus, full-value expansion and
previous/next change navigation. Field navigation searches the same logical machine across the
retained history, independently of list filters, using recorded before/after changes.

Bookmarks and the last 50 inspection locations retain event IDs and are pruned on eviction.
One comparison A retains display fields, not an executable snapshot. Navigation restores filters,
the event/graph tab and field inspection settings; A remains pinned until cleared or replaced.
These annotations last for this panel state and are not exported with recordings.
Register each lifetime with `timeline.machine("City Weather", stableId = "city-weather")`.
Use the same stable ID across recreations of one logical machine and distinct IDs for unrelated
machines, even when their display names match. The default stable ID is the supplied name.
The filter groups all lifetimes by this ID and shows only their display name; instance IDs
remain available in recorded entries. Continuing from history preserves the logical filter.
UI labels follow the device language: Russian and English are supported, with English as the fallback. Machine names, payloads and diagnostic data remain verbatim. `formatHistoryTime` uses the device locale and local time zone;
Android also respects the system 12/24-hour preference.

The application creates the timeline before starting executors, supplies immutable snapshots
and structured `StateField` values (or `historyFields(JsonElement)`), and owns restoration.
`HistoryPanelState` pins selected and preview entries even after ring-buffer eviction.
Selecting an entry immediately changes `state.preview`, which the host renders
without interactive handlers. Switching to Live clears preview. Continue here invokes the host's
callback for the selected preview; `canContinue` can reject unsupported checkpoints. The library records only machine events and has no screen or navigation model. The host owns
navigation, cancellation and safe executor restart. The weather example demonstrates this
using `resumeMachineStore` and read-only weather requests.
For a precise explanation use the additive overload with
`continuation = { snapshot -> HistoryContinuation.Available("Unfinished reads will restart.") }`
or `HistoryContinuation.Unavailable("A command completion has not been processed.")`.
The host supplies localized messages based on its actual executor constraints; the panel also
shows how many later retained events will be removed. The Boolean overload remains supported
with a generic explanation. `HistoryRecordingPanel` exposes the inspection tools without Live
or continuation controls.

The timeline is bounded by entry count, not bytes. It stores no files and does not automatically
capture machines. Keep snapshots immutable and install recording even when the panel is hidden.
The panel should receive bounded layout constraints (for example a right-hand app sidebar).

## Interactive machine graph

On wide panels the event list stays visible while the detail area switches between **Event**
and **Graph**. Below 640 dp, selecting an event collapses the list to give the graph and transition
more height; **Show event list** reopens it without losing selection.
Supply typed graph data for each selected entry:

```kotlin
HistoryPanel(timeline, panelState, onContinue = ::resume, graph = { entry ->
    HistoryGraph.Machine(machine.chart, entry.snapshot.configuration.active.toSet())
})
```

For multiple machine definitions, resolve the definition using `entry.machine.stableId` and
read active IDs from that entry's immutable snapshot. Return `HistoryGraph.Unavailable` for
entries without a definition (also the default). The weather example provides both search
and parallel weather graphs. The graph always describes the **selected event**, even when
Live mode lets the application run independently.

The renderer uses Compose Canvas, with no WebView or graph-engine dependency. Compound and
parallel containers retain declaration order; local transitions stay in their own container.
Routes use separate ports and lanes. Crossing edges have a background gap; arbitrary graphs
can still have crossings. Horizontal action badges are measured before layout; long text wraps
and containers grow to reserve the full label width and height. No action label is omitted or
ellipsized. Parallel regions reserve separate label columns for cross-region transitions.
Click an action badge to isolate its exact transition and endpoints, including when several
transitions have the same name. The detail area shows source, target, trigger, guard, effect
and transition kind. Selecting a state instead shows all its outgoing actions.
This is a diagram of declared transitions, not a prediction that every guard will pass.

Drag or scroll to pan; pinch, Ctrl/Command + scroll, or use +/− to zoom (10–400%). **Fit**
centers the whole diagram. Zoom is anchored at the pointer and preserved per logical machine
across history selection and tabs while the panel remains mounted. Active IDs are highlighted
from the selected checkpoint; graph inspection never dispatches actions or resumes execution.

### macOS desktop trackpad

Touch pinch and native macOS trackpad magnification are separate input paths. The JVM renderer
registers the JDK's `MagnificationListener` on the containing Swing root pane while the graph
is mounted and removes it on disposal. Only gestures over the graph affect its viewport.
For a modern JDK, a macOS host application must export that platform API at startup:

```kotlin
compose.desktop.application {
    if (System.getProperty("os.name", "").startsWith("Mac")) {
        jvmArgs += "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
    }
}
```

Weather already sets this flag. Other targets do not load Apple classes. Without the export,
zoom buttons and Ctrl/Command-scroll remain available; the native listener is not installed.

## Embeddable panel

`TimeTravelPanel` is a compact alternative to the full inspector. It has one machine picker,
back/next/verify controls, a history and expandable details, with no tabs or app navigation.
The host owns its placement and size. Wide bottom docks use horizontal history; tall, narrow
sidebars use vertical history. Navigation stays visible while details scroll. It follows the
host's `MaterialTheme`, including dark mode.

```kotlin
// Bottom dock: keep the application visible above it.
Column(Modifier.fillMaxSize()) {
    AppPreview(Modifier.weight(1f))
    TimeTravelPanel(inspector, timeTravel = controls, modifier = Modifier.height(300.dp))
}

// The same panel to the right (swap the children to put it on the left).
Row(Modifier.fillMaxSize()) {
    AppPreview(Modifier.weight(1f))
    TimeTravelPanel(inspector, timeTravel = controls, modifier = Modifier.width(320.dp).fillMaxHeight())
}
```

Overloads accept an `InspectorState` alone for live/recorded inspection, a `ReplayControls`
for one machine, a `GroupReplayControls` for group replay, or a `GroupTimeTravelControls`
for replay and isolated experiments. Filtering changes displayed history, never the group
cursor. In an experiment the panel exposes application-supplied actions and command answers,
completion/failure and virtual clock controls. Replay navigation returns with its original
position and verification result intact.

The panel does not open a window, start a recorder, replace the application's state or call
command handlers. Install recording before the bug happens. Drive an explicit, IO-free app
preview from the selected replay/branch snapshots; the [MVI weather demo](../examples/weather/README.md)
demonstrates application-frame preview and continuation with the newer HistoryPanel.

For runnable recording, preview, continuation and view-only file import, see the
[Weather application](../examples/weather/README.md).

A Compose Multiplatform screen over the [actron-timetravel](../actron-timetravel/README.md)
inspector: the debug UI of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md) §11, for
debug builds only. It shows what `Inspector` computed and moves a `ReplaySession`; it runs no
Store and reaches no network.

- **`InspectorScreen(state)`**: the mode and the group's completeness on top (Live, Inspect,
  Replay and Branch in their own colours), the Stores with capability, counts, recording status,
  completeness reasons and why a replay is unavailable on the left, the timeline in the middle
  (a click narrows it to a Store, a click selects a position), the selected position on the
  right: input and cause, outcome, active nodes, the snapshots before and after with what
  changed when a recording is attached, commands, timers, events, failures. Stacked on a
  narrow screen.
- **`InspectorState`**: the inspector, the selection, the Store filter and the mode, as Compose
  state; `rememberInspectorState(inspector)` for a history, `rememberLiveInspector(session)`
  for a running session, rebuilt every half second.
- **`ReplayControls(store, session)`** and the replay bar: back, forward, seek, verify; a
  disabled button says why (the end of the recording, a divergence). The position panel then
  shows the machine's definition as Mermaid with the replay's active states highlighted
  (`toMermaid(active)`), selectable for pasting into a renderer.
- **`GroupReplayControls(session)`**: one cursor for all recorded machines, with back,
  forward, seek and group verification. The timeline follows `GroupRecording.order`;
  filtering by Store only changes the displayed rows. The detail panel shows the selected
  input's recorded before/after snapshots and every member's current snapshot, executor
  checkpoint and active states. A divergence identifies the failing input and member and
  stops forward movement before changing their state.
- **`BranchControls(branch, machines, inputs)`** and the branch panel: every member's
  snapshot, clock, awaiting and queued commands with buttons to complete, fail or answer
  them, the scripted `BranchInput`s the application allows (a `Dispatch`, a `Feed`, an
  `Answer` with a label), the clock's advance, the definition with the active states, and
  every decision made with what it changed. The branch decides with the pure machines;
  nothing runs.
- **`GroupTimeTravelControls(replay, routes, inputs)`**: the complete replay/experiment
  workflow. Branch Here creates an isolated branch from every member's checkpoint at the
  current global position. The screen switches to Branch mode, shows the origin and pauses
  replay navigation. Return to Replay discards the experiment and restores the replay UI at
  the original cursor, including any divergence and verification result.

Status: **supported API**, in the fork [roman-n1/actron](https://github.com/roman-n1/actron).

## Dependency

```kotlin
// debug source set only: this module must not be in a release dependency graph
implementation("io.github.roman-n1:actron-timetravel-compose:1.0.0-beta.1")
```

The library's CI runs `checkDebugGraph`: no production module of actron depends on
`actron-timetravel` or this module, so what an app keeps in a debug source set stays there.

## Quick start

```kotlin
@Composable
fun DebugInspector(session: RecordingSession, recordings: Map<StoreInstanceId, Recording<*, *, *, *>>) {
    val state = rememberLiveInspector(session, recordings)        // mode Live, refreshed as the journal grows
    InspectorScreen(state)
}

@Composable
fun ReplayOf(recording: Recording<Ctx, Act, Cmd, Ev>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, mapOf(storeId to recording)), InspectorMode.Replay)
    val controls = remember { ReplayControls(storeId, ReplaySession(machine, recording)) }
    InspectorScreen(state, replay = controls)
}

@Composable
fun ReplayGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members), InspectorMode.Replay)
    val controls = remember(recording, machines) { GroupReplayControls(GroupReplaySession(machines, recording)) }
    InspectorScreen(state, groupReplay = controls)
}

@Composable
fun ExperimentWithGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members))
    val controls = remember(recording, machines) {
        GroupTimeTravelControls(
            GroupReplayControls(GroupReplaySession(machines, recording)),
            routes = routes, // application-supplied GroupBranch.Route mappings; empty means no local delivery
            inputs = listOf(BranchInput.Dispatch(storeId, "Load", Act.Load)),
        )
    }
    InspectorScreen(state, timeTravel = controls) // mode comes from the workflow
}

@Composable
fun BranchOf(session: GroupReplaySession, machines: Map<StoreInstanceId, Machine<*, *, *, *>>) {
    val branch = remember { BranchControls(session.branch(routes), machines, listOf(BranchInput.Dispatch(storeId, "Load", Act.Load))) }
    InspectorScreen(state, branch = branch)                       // mode Branch: the panel decides what the buttons send
}
```

Group replay requires a recording and a matching machine definition/version for each member.
Its cursor counts inputs in the group recording, independently of the diagnostic journal:
partial journals and Store filters do not change those positions. Seeking and going back
restore recorded checkpoints without verifying skipped inputs. Forward re-decides the next
input and checks bridge/source causality; Verify checks the whole group without moving the
cursor. Timers, command queues and pending effects are displayed as recorded state; replay
does not start command handlers, timers or network requests.

Experiments begin from recorded executor checkpoints, so awaiting commands can be answered
and recorded timers can fire when the virtual clock advances. Application-supplied routes
map branch effects to local member actions; recorded bridge metadata cannot reconstruct
those functions. External sources use scripted `BranchInput.Feed` data. While the workflow
screen owns the cursor, do not move its replay session externally. Returning leaves all
recorded checkpoints untouched; starting another experiment creates a fresh branch.

The replay position also contains `StateChartPanel`: an interactive diagram with active and
previous nodes, selectable transitions, timer details and actual guard outcomes. The explicit
“Explain next input / check invariants” button computes one pure decision and checks the selected
snapshot; composition itself never executes guards or business IO. The panel renders metadata,
with context/action payloads left to an application's explicit projection.


`ResizableHistoryLayout` places the inspector on the right of a wide window or below the app
on a narrow window. Drag its separator to adjust width or height. Retain a
`HistoryPanelLayoutState` in the host to preserve the size when hiding the panel or navigating.
The separator also exposes an accessible range action.


Selecting entries or switching History/Live does not remove history. After the continuation
callback succeeds, the panel removes the abandoned future, keeping the selected event,
earlier entries and any new events emitted during restoration. Event IDs are never reused.
Technical diagnostics remain in the recording model but are not displayed by HistoryPanel.

### Graph diagnostics

The graph automatically inspects `definition.diagnostics`. Clean graphs show no banner.
Errors use the error palette; warnings use a separate tinted banner. Expand the banner to
read localized diagnostics, then select a problem to focus its state or exact transition.
The viewport pans and increases scale when needed. If an endpoint is missing, the existing
source/target is focused; an entirely missing node cannot be drawn.

Weather's Settings offers **Graph diagnostic demo** while the debug panel is enabled.
Cycle through Off / Warnings / Errors. It adds unreachable/no-exit states and a shadowed
transition to an inspector-only copy; Errors also adds a missing endpoint. This never
modifies the executing machine, history checkpoints, HTTP requests or saved state.

### Transition coverage

Supply `HistoryGraphCoverage.Recorded(hits)` to `HistoryGraph.Machine.coverage` to show
executed/declared transition counts. Keys are `TransitionId` indices in that exact graph
version; absent keys mean zero executions. Omit coverage to retain the ordinary graph.
Executed arrows are solid and accented, unexecuted arrows dashed. Selecting an arrow still
highlights it separately and its card shows the exact execution count, including self-loops.
This is observed coverage, not proof that unexecuted declarations are unreachable.

Production code can accumulate immutable `TransitionCoverage` from committed decisions
without a test dependency or re-evaluating guards. Record each decision exactly once; do not
register the same accumulator for both explanation and commit callbacks. Keep it scoped to
the definition/version. Test scenarios already return `MachineCoverage`; use
`HistoryGraphCoverage.Recorded(report.transitions.covered.associateWith { 1L })` to display
binary test coverage (the report does not retain execution frequencies).

Weather stores cumulative counts in each history frame, grouped by stable machine identity.
The graph shows counts up to the selected event, including earlier events evicted from the
ring buffer. Continuing from history branches from those counts; discarded future decisions
are excluded. Counts are session-only diagnostic data and reset on process restoration.

### Imported recordings

`HistoryRecordingPanel(recording, state, graph = ..., actions = ...)` is a separate inspection
surface. It provides event selection, differences, graph and filtering, with no live switch,
continuation callback, truncation or effect execution. The header explains why imported files
cannot continue execution. Render `state.selection.single().snapshot` using your stateless
screen composables, with interactions disabled. Do not instantiate screen components or stores
from an imported snapshot. Keep the existing application's state and inspector separate and
return to them when the viewer closes.

Both panel variants accept an `actions` slot on the right of the machine filter. Weather places
Import and Export with directional icons there, plus Back to session while inspecting a file.
File dialogs remain in the host, outside the library. Weather uses Android's Storage Access
Framework, Desktop's native file dialog and the browser's file input/download mechanism.

## Transition coverage on the graph

The graph shows active states, transition coverage and selection. Coverage and zoom controls
share the header; the coverage text uses at most three lines. Selecting a state or transition
shows its definition and execution count, without execution-time measurements or heat coloring.
`HistoryGraphProfile` remains accepted for API compatibility, but its timing payload is not rendered.
Weather no longer collects or retains profiler snapshots.

## History panel internals

`HistoryPanel` and `HistoryRecordingPanel` remain the public entry points. The facade collects
retained entries, derives the filtered list and owns continuation ordering: capture the old
head, call the host, truncate the abandoned future, then clear selection. Entries appended by
the host during restoration survive that truncation.

`HistoryPanelState` owns selection and preview; `HistoryInspection` owns filters, bookmarks,
field inspection and navigation. UI components read these existing owners directly:

- `HistoryPanelHeader` renders the live/history status, mode switch and machine picker.
- `HistoryEventList` renders search, bookmarks and event rows.
- `HistoryPanelLayout` keeps list scroll/collapse state while placing the list and selected
  event for wide or narrow hosts. Graph viewports remain owned by the facade and keyed by
  logical machine, so changing width or tabs does not replace them.
- `HistoryPanelNavigation` renders the event cursor and handles keyboard navigation after
  child editors and graphs have had a chance to consume their keys.
- `HistoryEventDetail` renders the selected event, its payload, state transition and the
  host-supplied continuation capability. Payload expansion is scoped to the selected event.

The imported recording viewer uses the same inspection components, keeps the host's actions
slot and exposes neither live mode nor continuation. Public Kotlin APIs are unchanged by this
split. The JVM API dump also includes compiler-generated `ComposableSingletons`; their entries
are regenerated when composable bodies move between files, with API checks still enabled.


## Inspector screen internals

`InspectorScreen.kt` retains all existing public entry points in the same JVM facade. Internal
layout, Store cards, timeline, selected position, replay bar and branch rendering live in
separate components. Journal presentation mappers prepare diagnostic text, availability and
colors; branch mappers prepare the current command IDs and scripted actions. These are
short-lived immutable display snapshots; `InspectorState` and the controls still own state.
Wide screens retain their side-by-side panes; narrow screens retain the stacked
journal/replay/branch sections. Each nested scrolling pane is bounded by the viewport height,
avoiding the original infinite-height measurement failure. Renderer callbacks continue to
call the same controls.

The project follows the [Compose presentation policy](../skills/compose-presentation/SKILL.md).
`checkComposeNoBooleanLogic` rejects new boolean operators in composable bodies and `uimodel`
packages. It is part of `detekt` and CI, with an exact shrink-only inventory for older files.
Inspector and the extracted History panel components are outside that remaining debt.

The Inspector split also adds generated klib stability metadata for internal presentation
variants. Authored JVM/klib signatures remain unchanged; generated entries are reviewed
separately rather than excluded from API validation.


## Machine graph internals

`MachineGraph` keeps the existing graph/coverage/profile contracts. Layout is a pure
`layoutGraph(definition, measureLabel)` calculation: `GraphRoutingMetrics` reserves measured
label columns and routing space, `GraphNodePlacement` places the hierarchy, and
`GraphEdgeRouting` allocates ports and orthogonal lanes. Original transition IDs, declaration
order, malformed hierarchy handling and geometry remain unchanged.

`GraphInteraction` owns selection for the current definition;
`GraphSurface` owns the canvas size and `GraphViewport` retains scale/offset for the logical
machine. Snapshot changes keep selection and zoom; definition changes reset selection.
Measurement and size updates do not silently refit an initialized viewport.
`GraphPresentation` derives highlight, coverage and stroke choices; `GraphDrawing`
renders those immutable decisions. Controls, gestures and detail cards use the same owners.
The graph components contain no remaining Compose boolean-operator debt.


## Selection lifetime and remaining analysis inventory

Live inspector refresh follows the selected journal event within the same run, using its
subject, event kind and group sequence. A sliding retained window changes its view index;
eviction, a different run or an event without a stable position clears selection.
`HistoryPanelState` binds to its timeline or imported recording. The first attachment keeps
host-preselected entries. A different source clears selection, preview, navigation, comparison,
bookmarks and filters; hiding and reopening the same recording keeps its selection.
Graph transition details always use the original `TransitionId`, including after invalid
edges have been omitted from layout.

`TimeTravelPanel` retains its public overloads in the same JVM facade. Layout, timeline,
navigation, journal, replay and branch components have separate responsibilities.
`StateChartPanel` owns selection; pure presentation functions prepare a single grid,
activity, valid edges and recorded details. Drawing never evaluates guards or machine inputs.

The module has no remaining Compose boolean-operator baseline entries or wildcard imports.
The remaining three Detekt entries are deliberate API obligations:

- `InspectorScreen` keeps the existing public overloads and component functions in their
  original JVM facade for binary compatibility.
- `BranchControls` exposes the existing branch operation facade; its twelve public methods
  cover snapshots, commands, inputs, virtual time and display definitions. Splitting the
  public surface to satisfy a counter would break existing callers.
- `ChartInspection.Checkpoint.withExplanation` implements the public sealed contract as a
  no-op because a checkpoint has invariant observations and no next-decision explanation.

All other module baseline entries were removed after fixing their causes. Rule thresholds,
exclusions and policy enforcement remain unchanged.

UI translations use native XML resources: [localization guide](../doc/guides/localization.md).
