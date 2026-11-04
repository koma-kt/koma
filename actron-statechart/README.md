# actron-statechart

For new MVI screens, start with the [typed state DSL](#state-types-shared-by-graph-ui-and-recordings)
and [MachineStore threading and request handlers](../doc/guides/machine-mvi.md).
The explicit-ID chart examples below document the lower-level API, which remains supported.

## First: a phase is different from its data

A search screen can be **Loading** while its context contains a query and selected contacts.
Loading is a node because it owns a request and accepts particular actions. The query and
selection are ordinary context fields, not extra nodes. The runtime combines active nodes
with context into `MachineSnapshot<C>` for the UI.

| Shape | Behaviour | Example |
|---|---|---|
| `AtomicState` | No children | Loading, Content, Error |
| `CompoundState` | Exactly one child active while the parent is active | Connected contains Idle or ChatOpen |
| `ParallelState` | All child regions active, each with its own configuration | Connection and upload progress |
| `HistoryState` | A transition target that restores remembered children, not a lasting active phase | Resume a paused editor; shallow restores one level, deep restores nested levels |
| `Trigger.After` | A timed transition while its source is active | Debouncing → Searching after 350 ms |

Start with a flat chart. Introduce hierarchy when children share a lifetime, parallel regions
when concerns really evolve independently, and history when resuming matters. See
[hierarchy/history/parallel examples](#hierarchy-history-parallel-regions-timers).

## A chart is transition data

```kotlin
import actron.core.Action
import actron.statechart.*

data object ToggleDoor : Action
val closed = StateId("Closed")
val open = StateId("Open")
val doorChart = StateChartDefinition(
    initial = closed,
    states = listOf(AtomicState(closed), AtomicState(open)),
    transitions = listOf(
        Transition(closed, open, ActionMatcher.of<ToggleDoor>("Toggle")),
        Transition(open, closed, ActionMatcher.of<ToggleDoor>("Toggle")),
    ),
)
val diagram = doorChart.toMermaid()
```

Creating a definition does not start a coroutine or open a door. It gives the validator,
diagram exporter and runtime the same transition model. A **guard** decides whether a
transition may fire; an **effect** changes context or describes work. Their labels are in the
model and their functions are supplied to its executor.

Use the [Machine runtime](#replay-ready-machine) for explicit commands and deterministic replay.
The [loading/retry example below](#quick-start) shows the adapter. The
[root counter](../README.md#3-a-machine-makes-decisions-io-runs-afterward) shows a pure decision;
the [Weather example](../examples/weather/README.md) adds recording and history.

## Module overview

Statecharts for [Actron](../README.md): describe a screen's behaviour as plain data, check it,
draw it, generate test paths from it, and run it as an ordinary Actron `Store`.

- **Model.** `StateChartDefinition` is an immutable list of states and transitions: atomic,
  compound (hierarchy), parallel (regions) and history states; transitions fired by actions or
  timers, with guard and effect labels.
- **Tools.** `validate()`, `toMermaid()`, `shortestPathTo()`, `transitionCoveragePaths()`, and the
  `StateChartConformance` plugin, which checks a running Store against the chart.
- **Runtime.** `MachineStore` queues inputs, commits machine decisions and executes commands.
  Compose observes its snapshots; application code does not own another mutable configuration.
- **Machine.** `actron.statechart.machine` decides a chart as a pure function over snapshots, with
  commands, timers and events as data: the replay-ready path of the time-travel work (see below).

The semantics follow SCXML (Harel statecharts): external transitions, exit innermost first, enter
outermost first, inner transitions take priority over outer ones. The module uses the fork's
`actron-core` (`dispatchIf`, `validateRecovery`, `StoreProbe` for `MachineStore`'s idle gate,
`InputId`, the open `StoreScope`; see the
[divergence inventory](../doc/internal/design/2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400)),
so it is built and published together with it and is not meant to run against another version.

Status: the **pure chart/machine model, snapshots, decisions and checkpoints are stable in the
fork**, held by `apiCheck`. The Store adapters and their constrained runtime settings are also
stable. The MachineStore builder cannot install arbitrary handlers or recovery.
External sources, feed and source registration are supported without opt-in.
The module lives in the fork
[roman-n1/actron](https://github.com/roman-n1/actron), not in upstream Koma.

## Behaviour queries and documentation

`machine.declaredActions(snapshot)` lists active transition/handler matchers without running
guards. `machine.availableActions(snapshot, actualActions)` evaluates selection for supplied
typed payloads, reporting executable, blocked, undeclared and failed-guard cases. It runs no
reducers/hooks/handlers or IO; eligibility does not guarantee full macrostep success.

Supply diagnostic metadata with `guard("online", "Connection.Online is required") { snapshot, action -> … }`.
`machine.explainWhyRejected(explainedDecision)` reuses actual observations without another guard
evaluation; the snapshot/action/explicit-time overload simulates a complete pure decision.

`before.behaviouralDiffTo(after)` compares explicit versions of one logical Machine, including
structural changes and migration/version obligations. It cannot inspect executable rule bodies.
`chart.transitionMatrix().toMarkdown()` renders a state/trigger matrix;
`toTransitionTableMarkdown()` renders detailed declaration rows with guards, effects and indices.
These are declaration views; hierarchy and guards still determine actual runtime selection.
See [derived behavioural feature contracts](../doc/internal/design/2026-10-06-derived-behavioural-features.md).

## Dependency

The fork publishes every module as `io.github.roman-n1:<module>:5.0.0-alpha.1` to Maven Central from
a GitHub pre-release (`.github/workflows/publish.yml`); until a release is published, include the
fork as a Gradle composite build (for example as a git submodule), which substitutes the same
coordinates:

```kotlin
// settings.gradle.kts
includeBuild("actron")
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:actron-statechart:5.0.0-alpha.1")
        }
    }
    compilerOptions {
    }
}
```

Gradle substitutes the included project for these coordinates. The module brings the fork's
`actron-core` with it (as an `api` dependency, `io.github.roman-n1:actron-core`).
Actron and upstream Koma have separate packages; their types are not interchangeable. Targets: Android, iOS (arm64,
simulator arm64), JVM, JS and Wasm.

## Quick start

Use the [typed counter](../README.md#1-a-store-for-a-counter) or
[Weather's search machine](../examples/weather/shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt).
`typedStateMachine` derives the graph from state types; `MachineStore` executes its decisions.
For Decompose, [`retainedMachine`](../actron-decompose/README.md) owns creation, startup and cleanup.
The standalone Store DSL and coroutine StateChartStore adapter are no longer application APIs.


The explicit-ID examples below use this model:

A list screen that loads, retries a failed load up to three times, and shows an error after that.

```kotlin
sealed interface ListAction : Action {
    data object Load : ListAction
    data class Loaded(val items: List<String>) : ListAction
    data object Failed : ListAction
}

sealed interface ListEvent : Event {
    data object GaveUp : ListEvent
}

data class ListContext(val items: List<String> = emptyList(), val attempts: Int = 0, val query: String = "")

val idle = StateId("Idle")
val loading = StateId("Loading")
val content = StateId("Content")
val error = StateId("Error")

// The first transition whose guard holds is taken, so retry comes before giveUp.
val retry = Transition(loading, loading, ActionMatcher.of<ListAction.Failed>("Failed"), guard = GuardKey("canRetry"), effect = EffectKey("countAttempt"))
val giveUp = Transition(loading, error, ActionMatcher.of<ListAction.Failed>("Failed"))

val listChart = StateChartDefinition(
    initial = idle,
    states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(content), AtomicState(error)),
    transitions = listOf(
        Transition(idle, loading, ActionMatcher.of<ListAction.Load>("Load")),
        Transition(loading, content, ActionMatcher.of<ListAction.Loaded>("Loaded"), effect = EffectKey("storeItems")),
        retry,
        giveUp,
        Transition(error, loading, ActionMatcher.of<ListAction.Load>("Load"), effect = EffectKey("resetAttempts")),
    ),
)
```


## Hierarchy, history, parallel regions, timers

The full example is the messenger in
[`src/commonTest/kotlin/actron/statechart/example/MessengerChart.kt`](src/commonTest/kotlin/actron/statechart/example/MessengerChart.kt):
sign-in, a session with a connection region and a UI region, reconnect backoff, message resend,
and settings that return to the same chat. It is a legacy runtime regression fixture. For application
code, use the [typed hierarchy DSL](#typed-hierarchy-history-and-completion). The structural pieces:

```kotlin
val states = listOf(
    // Hierarchy: a compound state has children (nodes whose parent is it) and an initial child.
    CompoundState(app, initial = signedOut),
    AtomicState(signedOut, parent = app),
    AtomicState(signingIn, parent = app),

    // Parallel regions: every child of a parallel state is active at the same time.
    ParallelState(session, parent = app),
    CompoundState(connection, initial = connecting, parent = session),
    AtomicState(connecting, parent = connection),
    AtomicState(online, parent = connection),
    AtomicState(backoff, parent = connection),
    CompoundState(ui, initial = chats, parent = session),

    // History: a transition into ChatsHistory restores what was active in Chats when it was left.
    CompoundState(chats, initial = chatList, parent = ui),
    HistoryState(chatsHistory, parent = chats, deep = true),
    AtomicState(chatList, parent = chats),
    AtomicState(settings, parent = ui),
)

val transitions = listOf(
    // A transition from a compound state leaves it from any of its descendants.
    Transition(session, signedOut, ActionMatcher.of<MessengerAction.SignOut>("SignOut")),
    Transition(chats, settings, ActionMatcher.of<MessengerAction.OpenSettings>("OpenSettings")),
    Transition(settings, chatsHistory, ActionMatcher.of<MessengerAction.CloseSettings>("CloseSettings")),
    // Timers: fire after the source has been active for the delay; cancelled when it is exited.
    Transition(backoff, connecting, Trigger.After(2.seconds), guard = GuardKey("quickReconnect")),
    Transition(backoff, connecting, Trigger.After(30.seconds), guard = GuardKey("slowReconnect")),
)
```

- **Hierarchy.** Transitions are looked up from the active leaf outwards, so an inner transition
  beats an outer one. Every transition is external: a self-loop exits and re-enters its source.
- **History.** Shallow history (`deep = false`) restores the active child; deep history restores
  the active leaves. Until the parent has been exited once, the history enters its `default`, or
  the parent's initial child.
- **Parallel.** One action may fire one transition per region, all in one step. A transition out
  of the parallel state (like `SignOut`) exits every region. `definition.activeLeaves(snapshot.configuration)`
  lists one leaf per active region.
- **Timers.** `MachineStore` runs timers as coroutines, so `runTest` virtual time
  drives them in tests. A timer's guard is asked when it fires, with `TimerFired` as the action.

## State-local Kotlin behavior

`behaviorStateMachine` offers `state<Phase>`, `on<Action>`, suspend `onEnter`, and
`transitionTo(state = ...)` on the same decision engine. In both handler scopes, `val state`
is the complete immutable application snapshot captured at invocation. It has no setter;
propose every update by passing a complete replacement to `transitionTo`. The scope keeps
reading the original snapshot even after that call. Keep values deeply immutable.

For a wrapper such as `CitySearchState(query, phase, revision)`, supply
`phaseOf = { state: CitySearchState -> state.phase }`. This selects the graph node without a
write lens. For a state hierarchy used directly as the snapshot, omit `phaseOf`.

```kotlin
on<CitySearchAction.ChangeQuery> { action ->
    transitionTo(state = state.copy(
        query = action.query,
        revision = state.revision + 1,
        phase = RequestState.QueryChanged,
    ))
}
```

`onEnter` executes outside action processing and is cancelled on state exit. Its transition
results pass through the queue and are checked against their activation before replacing the
whole state, so an obsolete coroutine cannot overwrite a newer snapshot. Resume restarts only
unfinished entry bodies; history replay performs no I/O. Arbitrary Kotlin conditions do not
declare a complete static reachable graph. See the [Weather example](../examples/weather/docs/architecture.md).

## Replay-ready machine

`actron.statechart.machine` runs a chart as a pure decision function instead of a Store with
suspending hooks. It is the foundation of the time-travel work
([handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md), stage 2): a `Machine`
decides, and a `MachineStore` (below) executes its decisions.

```kotlin
sealed interface ListCommand {
    data class Fetch(val query: String) : ListCommand
}

val listMachine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion("3"), listChart) {
    guard("canRetry") { snapshot, _ -> snapshot.context.attempts < 3 }
    effect("storeItems") { context, action -> context.copy(items = (action as ListAction.Loaded).items) }
    effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
    effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }

    // A rule is a pure function: it reads the context and the input, assigns the context,
    // registers commands as data and emits events as data. Nothing runs here.
    onEnter(loading) { command(ListCommand.Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
    onEnter(error) { event(ListEvent.GaveUp) }
}

var snapshot = listMachine.initialSnapshot(ListContext())
val started = listMachine.decide(snapshot, MachineInput.Start(now = MachineTime.Zero))
snapshot = started.snapshot

val decision = listMachine.decide(snapshot, MachineInput.Dispatch(ListAction.Load, now = MachineTime(1.seconds)))
decision.outcome        // Handled
decision.transitions    // [T0]: Idle -> Loading
decision.commands       // [CommandRegistration(c1, Fetch(query), scope = a3, lane = load, policy = Latest)]
decision.timersScheduled // the timers of Loading, due at now + delay
decision.snapshot.revision // 2
```

What a decision holds and what the machine guarantees:

| Piece | Meaning |
|---|---|
| `MachineSnapshot` | Configuration, context, the activation of every active node, registered commands, scheduled timers, id counters. Plain data, a Actron `State`; the machine's part of a checkpoint. |
| `Lanes`, `ExecutorCheckpoint` | The executor's part: which commands run and which wait in which lane, as a pure value with the policies' rules; the checkpoint is a snapshot, the clock and the lanes, and checks that its commands are the snapshot's. |
| `MachineInput` | `Start`, `Dispatch`, `TimerFired`, `CommandResult`, `CommandCompleted`, `CommandFailed`; each carries the clock (`MachineTime`), so the machine never reads one. |
| `Decision` | `Handled` (revision + 1, also when the business data is equal), `Ignored(reason)` (the same snapshot object) or `Failed` (a rule threw; nothing registered). Plus the transitions, exited and entered activations, commands to register, scopes to cancel, timers to schedule and cancel, events to deliver. |
| Activations | Every entry into a node, a self-loop included, is a new `ActivationId`. Commands of an enter rule belong to it and are cancelled with it; a late `CommandResult` of a cancelled command is `Ignored(StaleCommand)`. |
| Timers | Data with a `TimerId` and a deadline of `now + delay`; a fired timer is spent whether or not its guard holds, a self-loop timer restarts with a new id. |
| Determinism | The same snapshot and input give an equal decision; ids come from the snapshot's counters, so a replay issues the same ids. |

An unexpected command failure arrives as `CommandFailed` and steps with a `CommandFailure`
action: declare `ActionMatcher.of<CommandFailure>("CommandFailure")` on a transition to react to
it. An expected failure of a service (a 404, an offline error) is not this; the handler turns it
into a typed `CommandResult`.

Building a `Machine` fails fast for a missing guard or effect
implementation, a rule for an undeclared node, undeclared endpoints, an instant timer loop.

### Handling an action without leaving a node

A self-loop exits and re-enters its node: its activation ends, its commands are cancelled and its
timers restart. For an update that must not do that (a selection while results are shown, a
filter remembered while idle) add an action handler:

```kotlin
onAction(search, ActionMatcher.of<SearchAction.ToggleSelect>("ToggleSelect")) {
    context = context.toggleSelect((action as SearchAction.ToggleSelect).contactId)
}
```

It runs only when no transition of the active configuration takes the action, so a declared
transition always wins; among handlers, the innermost active node with a matching handler wins,
then the first added. It may update the context, register commands (scoped to the node's current
activation) and emit events. The decision is `Handled` with a new revision and no transitions.

The stage 3 pilot,
[`AddressBookSearchMachine`](src/commonTest/kotlin/actron/statechart/example/picker/AddressBookSearchMachine.kt)
with [its scenarios](src/commonTest/kotlin/actron/statechart/example/picker/AddressBookSearchPilotTest.kt),
shows a whole screen built this way: a debounced search that the latest query supersedes, stale
answers ignored, closing during a request, selection without touching the search, a `UiMapper`
from the snapshot, and two tabs of the same screen recorded into one journal.

### Running a machine

`MachineStore` runs a `Machine` as a Actron `Store<MachineSnapshot<C>, A, E>`: decisions are
committed under the store's lock, and after each commit a scheduler starts the registered
commands through your `CommandHandler`, fires timers on the clock and delivers the events.

```kotlin
val handler = CommandHandler<ListCommand, ListAction> { command, results ->
    when (val c = command.command) {
        is ListCommand.Fetch -> results.result(ListAction.Loaded(api.items(c.query))) // an expected failure would be a typed result too
    }
}

val store = MachineStore(listMachine, ListContext(), handler, scope = appScope) {
    exceptionHandler(ExceptionHandler { log(it) })
    recordTo(session, StoreInstanceId("list-$tabInstanceId"))   // the journal, if you use one
}

store.dispatch(ListAction.Load)          // accepted as MachineInput.Dispatch(Load, clock.now())
store.state.collect { snapshot -> render(snapshot) }
store.event.collect { event -> toast(event) } // delivered after the commit, in decision order
```

What holds:

- A command starts only after the decision that registered it was committed, outside the lock,
  in `scope` (which must not use `Dispatchers.Unconfined`); it sees the committed snapshot in
  `currentState`. Its results, completion and failure come back as inputs; a result of a command
  the machine no longer holds is ignored.
- A command is cancelled when its activation exits, when a newer command supersedes it in a
  `Latest` lane, or when the store closes. `Sequential` and `Parallel(limit)` lanes queue,
  `DropIfRunning` drops; a dropped or superseded command is reported to the machine as
  `CommandAbandoned`. The bookkeeping is `Lanes`, a pure value the scheduler, a recording and a
  replay branch all run: a cancelled command holds its place in the lane until its job has ended.
- A decision that fails (a rule threw) commits nothing and cancels nothing; its cause reaches the
  exception handler. Domain recovery uses explicit machine inputs, not raw Store recovery.
- A restored snapshot that was already started starts over with its context: commands are not
  part of the snapshot. They are part of `store.checkpoint()`, the executor's state as data
  (`ExecutorCheckpoint`): the snapshot of the last decision it carried out, its clock, and every
  command of that snapshot in exactly one place, running or queued in its lane or ending with
  its last input on its way to the machine. `actron-timetravel` branches from it and records from it.
- `AdmissionPolicy.Bounded(n)` refuses a dispatched action while `n` accepted actions still wait;
  `store.admit(action)` says whether it was accepted, `dispatch` drops the answer. Inputs the
  machine's own commands and timers send bypass backpressure while the store is open.
  `Admission.Closed` means the store has closed and the input was not queued; do not retry that
  store. `Accepted` means queued, and a later close may discard the input before processing.
  Close clears inputs held by a group cut and releases their `dispatchAndAwait` callers;
  an individual await finishes when its own input finishes or is discarded, independent of
  later traffic. Calling `dispatchAndAwait` on an already closed store throws.

  **5.0 alpha migration:** `Admission.Closed` is a new sealed outcome. Add a terminal closed
  branch to exhaustive `when (admission)` expressions compiled against an earlier unreleased
  alpha. Capacity rejection remains `Rejected(pending, limit)`; closure is not backpressure.
- Effects reach the UI through `store.event` (transient: delivered to whoever collects now, or
  lost) or, when `MailboxConfig.policy` says `Retained` or `Latest(key)`, through
  `store.mailbox`: they wait there, with their `EffectId`, until a subscriber of
  `mailbox.subscribe()` acknowledges the `Delivery`; a subscriber that goes away hands what it
  was handling to the next one, up to the policy's `maxAttempts` (then the effect is discarded
  as `Exhausted`), `Latest` keeps only the newest of its key, the mailbox is bounded, and the
  pending effects are in the checkpoint. `session.effectsOf(id)` journals what the mailbox
  does. See the [ADR](../doc/internal/adr/2026-09-30-effect-mailbox.md); in Compose, the
  subscription is `MailboxEffect` (below).
- Closing a store with commands unfinished tells its observers once (`onClosed`): which never
  started and which were cancelled; `session.decisionsOf(id)` journals it as `CommandsAbandoned`.
- A `MachineGroup` joins stores that talk to each other: `route(from, to, map)` delivers a
  member's effects to another as `MachineInput.BridgeReceived` with a `MessageId` of the sender
  and the effect, decided like a dispatch and never refused; `group.checkpoint(timeout)` is a
  consistent cut of the whole group (every member's `ExecutorCheckpoint` and the messages in
  flight), taken by freezing the members' input queues, waiting for what they had accepted, and
  letting the held inputs in afterwards, in order; a member that does not settle aborts the cut
  and the group resumes. Give each member's store `group.member(id)` as an observer and
  `attach` the store once built; `route(fromMember, toMember, map)` checks the receiver's action
  type where the route is written, `removeRoute(route)` stops a route (`routeHistory` keeps it
  for a recording); `requestReply(requester, responder, name, request, reply)` registers a
  request route and a reply route as a named pair: a reply decided in the same step as the
  request names it in the journal (`BridgeSent.cause`), and a replay reports a reply whose
  sender had received no request of the pair (`ReplyWithoutRequest`). A member whose store closed, or that `detach`ed, gets no more messages: a
  message sent to it is journaled undelivered and a cut leaves it out; what a store holds when
  it closes is dropped and journaled as `BridgeDropped` (a detached store still decides what it
  holds until it closes); `attach` a new store to take part again. An
  `ExternalSource` (a pagination engine, a socket reader)
  feeds a member with `store.feed(source, action)`, an input the recording knows as the
  source's; attached with `group.source(it)`, it is paused before every cut and its
  `SourceSnapshot` (its own fields: generation, window, load state) taken after the members
  settle, so a branch can script its next data. `actron-timetravel` records and replays a group.
- With `actron-observability`, `recordTo(session, id)` in the configuration journals the store's
  inputs and commits, and `observers = listOf(session.decisionsOf(id))` adds the decisions
  themselves: transitions, activations, commands, timers, ignored inputs with their reason,
  refused actions. Commands and refused actions carry only what the describers you pass keep.

### External source adapter contract

`ExternalSource`, `MachineStore.feed` and source registration are supported API. A socket or
paging adapter must serialize `feed` and its admission cursor with `pause`:

- Advance the cursor when `feed` returns `Admission.Accepted`. It means the input is queued;
  the group waits for the member to decide it before taking the source snapshot. On
  `Admission.Rejected`, keep the pending item or explicitly record a deliberate drop.
- Keep blocking socket reads and retry/backpressure waits outside the feed lock. A quiet socket
  or a full member queue must still allow `pause` to complete. Data already read but not admitted
  waits until the source resumes and belongs after the cut.
- A successful `pause` transfers cleanup to the group, which calls `resume` once, including when
  another source or member fails. A throwing or cancelled `pause` must roll back its own partial
  pause before propagating the failure. Use non-cancellable cleanup if that rollback suspends.
- Keep a stable upstream sequence or page cursor when reconnects can redeliver data. `feed`
  does not deduplicate. The source snapshot can distinguish its admitted cursor from pending
  requests and connection generations.

The [paging contract tests](src/commonTest/kotlin/actron/statechart/machine/ExternalSourceContractTest.kt)
exercise rejection/retry, cancellation and timeout rollback, and data arriving during a cut.
The [JVM socket integration tests](src/jvmTest/kotlin/actron/statechart/machine/SocketSourceIntegrationTest.kt)
use real loopback TCP connections to check reconnect/redelivery, queued frames during a cut and
backpressure. These are adapter examples and tests; the library does not provide a socket or
pagination transport.

### Effects in Compose

Retained effects survive screen recreation with a live Store. `StateSaver` does not persist
the mailbox across process death. For that boundary, keep a pending business intent in durable
domain context and use a stable business id for idempotent handling. The
[archived durable outbox example](https://github.com/roman-n1/actron/blob/bb173b88/examples/durable-effects/README.md) uses real process crashes to
check effect/acknowledgement ordering and explains the required external transaction boundary.

[actron-statechart-compose](../actron-statechart-compose/README.md) subscribes a composition to the
mailbox: `MailboxEffect(store.mailbox) { delivery -> … }` runs the block for each `Delivery`, one
at a time, oldest first, and the block acknowledges it once the UI did what the effect asked. The
subscription lives as long as the composition: what was queued before it entered is delivered
when it enters; what it was handling when it left, unacknowledged, comes back to the next
subscriber with `attempt + 1`, up to the policy's budget. One `MailboxEffect` per mailbox at a
time, at the screen that owns the store, handling every kind of retained effect with a `when`.
The UI model is `viewStore.select { it.toUiModel() }` from `actron-compose`.

```kotlin
@Composable
fun ChatScreen(store: MachineStore<ChatContext, ChatAction, ChatCommand, ChatEffect>, navigator: Navigator) {
    val viewStore = rememberViewStore(store)
    val uiModel = viewStore.select { it.toUiModel() }
    MailboxEffect(store.mailbox) { delivery ->
        when (val effect = delivery.event) {
            is ChatEffect.OpenThread -> navigator.open(effect.threadId)
        }
        delivery.acknowledge()
    }
    ChatContent(uiModel, onSend = { viewStore.dispatch(ChatAction.Send(it)) })
}
```

### MachineStore runtime ownership

The public `MachineStore` facade coordinates runtime adapters, observers and resources. Internally,
`MachineStoreQueue` owns admission order, group freeze/thaw and close admission;
`MachineStoreWork` owns capacity reservations and correlations with core input IDs.
A shared cut gate orders offers and close, and only then acquires the work-tracking gate.
Core dispatch, observer callbacks and completion signals run outside both gates, including
when the core dispatcher executes inline and a callback dispatches another action.

Thaw transfers all held inputs before later offers. Capacity is released when processing
starts, or when an input is discarded. Cancelling a `dispatchAndAwait` caller does not withdraw
its accepted input. Close discards held/queued inputs and releases their waiters; inputs
already submitted to the core finish through its terminal traces. `closeAndJoin` additionally
waits for coroutine cleanup. `MachineStoreIdleWaiter` joins outstanding handoffs, core work
and the executor barrier; frozen inputs and running commands/timers remain checkpoint data.

`MachineStoreDecisions` evaluates each input once, reuses that computation for diagnostics,
and selects handled/ignored/failed outcomes. Only handled decisions stage a publication and
request a core snapshot commit. `MachineStorePublication` owns that pending decision, verifies
the exact committed snapshot and consumes it before callbacks. It submits executor work, then
notifies all commit observers, routes group messages, and delivers effects to patched plugins
in effect-major order. Suspension in a plugin retains the core processing lock; the next input
cannot replace the pending snapshot. Callback isolation and plugin-scope adaptation remain at
the Store boundary. Neither helper owns another writable state flow or coroutine scope.

### Testing a machine

A `MachineStore` is a Store actron-test can drive: `startAndAwait()` starts it, `dispatchAndAwait(action)`
admits the action like `dispatch` and returns once the machine decided it (the commit and the
observers, not the commands the decision started; a rejected action throws, and during a group cut
the call returns after the thaw), `awaitIdle()` waits until the executor carried out every
decision and every result it fed was decided (a command still running, a timer, a pending effect
are data of `checkpoint()`, not work to wait for), `patch {}` before the start sets the exception
handler, the saver or the policies and appends plugins, and `createRecorder()` records the initial
snapshot, every commit and every effect the decisions emitted, transient and retained alike. A plugin appended
this way sees the callers' actions (dispatched, fed, delivered), not the executor's inputs; its
`dispatch` goes through admission. Replacing or clearing the plugins and probing through a patch are
refused; `dispatchIf` is not supported. [actron-statechart-test](../actron-statechart-test/README.md)
adds the machine's own test kit on top: `MachineTestDriver` (a virtual clock, a scripted command
handler the test answers, a settle after every step, the recorder's cursor over the effects),
`settle()`, `pendingWork()` and `assertNoPendingWork()` that name the commands, timers and effects
still in hand.

```kotlin
@Test
fun aLoad_fetches_andShowsTheContent() = runTest {
    val store = MachineStore(machine, Ctx(), handler, backgroundScope, clock, StandardTestDispatcher(testScheduler))
    val recorder = store.createRecorder()
    store.startAndAwait()

    store.dispatchAndAwait(Act.Load)            // decided: Loading, the Fetch command registered
    runCurrent()                                // the executor runs the command, its result is decided

    assertTrue(store.currentState.isActive(content))
    assertEquals(listOf(Ev.Started, Ev.Done), recorder.events)
}
```

## Validation, Mermaid, paths and conformance

Check the chart in a unit test. Pass one sample of every action type so that overlapping matchers
(a supertype and a subtype matcher on one state) are found too:

```kotlin
assertEquals(emptyList(), listChart.validate(sampleActions = listOf(ListAction.Load, ListAction.Failed)))
```

`validate()` reports duplicate ids, unknown states, broken hierarchies, bad history states,
unreachable states, unguarded transitions that compete for one action or one delay, and more; see
`ValidationIssue`.

Draw it with `listChart.toMermaid()`, which returns a `stateDiagram-v2` you can paste into a
Markdown file. Asserting it in a test keeps the diagram in the docs honest.
`toMermaid(active)` highlights the given states (a snapshot's `configuration.active`), which is
how the inspector draws a replay's or a branch's position.

For executable paths, use [MachineTestDriver and graph scenarios](../actron-statechart-test/README.md).
They run the same machine decisions with explicit commands and virtual time. The
[Weather tests](../examples/weather/debugTools/src/commonTest/README.md) demonstrate generated
coverage, stale responses and command cancellation through the production machine API.
`shortestPathTo(id)` and `transitionCoveragePaths()` remain structural queries; they ignore guards.

## R8 and code shrinking

Nothing in the module reads class names, so a release build with R8 behaves like a debug build,
provided the chart does not rely on them either:

- **State ids are strings you write.** `StateId("Loading")`, never a class name.
- **Use `ActionMatcher.of<A>("Name")`.** It matches by type (`isInstance`); the name is only for
  diagrams and diagnostics. `ActionMatcher("Name")` without a type matches by the action's simple
  class name, which R8 renames: do not use it in app code.
- **Saved state is data.** `MachineSnapshot` holds `StateId` strings, the history and timer tokens keyed
  by transition index, so a `StateSaver` can persist it without reflection. Treat a change to the
  chart as a change to the saved format. The active nodes and history records are checked against
  the new hierarchy: a snapshot whose active nodes the chart cannot produce falls back to the
  initial configuration while retaining its context, and a history record the chart cannot restore
  is dropped on its own (the next transition into that history state takes the default target).
  Context schema migrations remain the application's responsibility.

## One machine runtime

All supported application reducers use our concrete `Machine` and `MachineStore`.
The former coroutine chart adapter exists only as test regression infrastructure. It is not an alternate
runtime or a compatibility path. See the [API decision](../doc/internal/adr/2026-10-25-statechart-only-api.md).

## Persistence across process restarts

`StateSaver` supplies storage and encoding through your implementation. Use durable storage for
process restarts; an in-memory saver only survives as long as its owner does. Keep an application
schema version beside the saved data and migrate or reject incompatible context before returning
a snapshot. `restore(initialState)` returns the saved snapshot or its supplied initial state; a decoding `Exception` is
reported to the Store's exception handler and also falls back to the declared initial state.
Configure an exception handler that reports persistence failures if you need to diagnose them.

A `StateSaver` restores only a snapshot. An already started snapshot is restarted at the current
machine's initial configuration with its restored business context; old commands and deadlines
are not present in that snapshot. A reset from another definition/version is reported.

To resume work, use [`actron-persistence`](../actron-persistence/README.md) and
`resumeMachineStore` with an executor checkpoint. That checkpoint contains command lanes and
timer deadlines. Pending read requests may run again; irreversible work needs an application
recovery policy. Weather demonstrates ordinary checkpoint restoration without debug recording.

## Limitations

- No release has been published yet; only the current API and recording format are supported.
- Structural paths ignore guard values; executable scenarios must provide inputs and payloads.
- Runtime configuration cannot install arbitrary state handlers or replace a snapshot.
- Context and nested data must be immutable. Pure guards, updates and hooks must not perform I/O.
- Snapshot persistence alone does not resume pending commands; use executor checkpoints.
- A failed decision commits no state or effects. Command failures return as machine inputs.
- In a hook, avoid naming surrounding parameters `context`, which shadows the hook's context.

## State types shared by graph, UI and recordings

When the complete screen state is one value, no context binding is needed:

```kotlin
data class CounterState(val count: Int = 0)
data object Increment : Action
val counter = typedStateMachine<CounterState, Increment, Nothing, Nothing>(
    DefinitionId("counter"), DefinitionVersion("1"), CounterState(),
) {
    state<CounterState> {
        internal<Increment> { state, _ -> state.copy(count = state.count + 1) }
    }
}
```

Use the explicit binding below when context also holds data shared between phases.

`typedStateMachine` binds a sealed state value in the context to the graph. Define the
variants once; separate StateId constants are not needed:

```kotlin
sealed interface Phase {
    data object Idle : Phase
    data object Loading : Phase
    data class Results(val items: List<String>) : Phase
}
data class Context(val phase: Phase = Phase.Idle, val query: String = "")

val machine = typedStateMachine<Context, Phase, Act, Command, Ev>(
    id, version, initial = Phase.Idle,
    readState = { it.phase },
    writeState = { context, phase -> context.copy(phase = phase) },
) {
    state<Phase.Idle> {
        on<Act.Search, Phase.Loading>(
            reduce = { context, action -> context.copy(query = action.query) },
        ) { _, _ -> Phase.Loading }
    }
    state<Phase.Loading> {
        onEnter { command(Command.Fetch(context.query)) }
        on<Act.Loaded, Phase.Results> { _, action -> Phase.Results(action.items) }
        after<Phase.Idle>(8.seconds) { Phase.Idle }
    }
    state<Phase.Results>()
}
```

The optional `reduce` updates shared context first; the final lambda constructs the destination
state from that updated context. The DSL writes the state value before destination entry hooks
run. `internal<Act>` can change shared data without exiting the state. An enforced invariant
rolls back decisions that change the phase type without changing the graph, including mistakes
in the binding or hooks. It also validates restored snapshots through `checkInvariants`.

UI reads the same sealed value with an exhaustive `when`; normal machine snapshots already
record it alongside the automatically derived graph configuration. No UI-specific state map
or Time Travel adapter is required. Graph IDs use state class simple names, so names must be
unique and stable. All targets and the initial state must be declared. Renaming a state type
requires a definition version bump. Existing explicit-ID DSLs remain supported.

## Typed parallel regions

Use `parallelStateMachine` for a single machine with independent, simultaneously active regions:

```kotlin
parallelStateMachine<Context, Action, Command, Event>(id, version) {
    region("Current", initial = Current.Idle,
        readState = { it.current },
        writeState = { context, state: Current -> context.copy(current = state) },
    ) {
        state<Current.Idle> { /* typed transitions */ }
        state<Current.Loading> { /* entry command, answers and timeout */ }
    }
    region("Hourly", initial = Hourly.Idle,
        readState = { it.hourly },
        writeState = { context, state: Hourly -> context.copy(hourly = state) },
    ) {
        state<Hourly.Idle> { /* typed transitions */ }
        state<Hourly.Loading> { /* independent command and timeout */ }
    }
}
```

A shared action can take transitions in both regions in a single decision. Reducers must
preserve sibling data (use `copy`); guards see the pre-step snapshot and reducers are applied
in graph order. Each region's loading activation owns its commands and timers, so retrying
one region does not cancel the other. The existing parallel graph runtime handles conflict
selection and cancellation; this DSL only builds its model and typed bindings. Per-region
invariants check state/data agreement. Persisted IDs are qualified by region name, e.g.
`Current.Loading` and `Hourly.Loading`. Region names must be unique and at least two regions
are required. Rename types/regions only with a definition version bump.

## Typed hierarchy, history and completion

The typed builder also supports nested compounds. Compound types identify structural nodes;
`readState(context)` holds the active **leaf** value. Every leaf in a hierarchical region
provides its value once at entry: `state(Phase.Loading)` for objects, or
`state<Phase.Results>(enter = { context -> Phase.Results(context.cachedResults) })` for data.
The entry factory runs before that leaf's user `onEnter` hooks, including on history restore.
Keep data that must survive leaving a phase in the ordinary context.

```kotlin
typedStateMachine<Context, Phase, Action, Command, Event>(
    DefinitionId("weather-session"), DefinitionVersion("1"), Phase.Paused,
    readState = { it.phase },
    writeState = { context, phase -> context.copy(phase = phase) },
) {
    state(Phase.Paused) {
        onHistory<Action.Resume, Phase.Active>()
        on<Action.Restart, Phase.Active>()
    }
    compound<Phase.Active>(initial = Phase.Loading) {
        history(deep = true)
        on<Action.Pause, Phase.Paused>()
        onDone<Phase.Finished>()

        state(Phase.Loading) {
            onEnter { command(Command.Load) }
            on<Action.Loaded, Phase.Ready>()
        }
        state(Phase.Ready) {
            on<Action.Finish, Phase.Done>()
        }
        final(Phase.Done)
    }
    final(Phase.Finished)
}
```

`history()` remembers one level, `history(deep = true)` remembers nested leaves. Before the
first exit it falls back to the compound's initial configuration. `onHistory<Action, Parent>()`
targets that declaration with no history ID or string key. History restores graph configuration,
**not old business data or command results**; enter factories rebuild leaf values from current
context and ordinary enter hooks run again. This differs from Time Travel inspection/replay.

`onDone<Target>()` fires when the compound completes. `always<Target>(guard = { ... })` fires
without another external action. Automatic microsteps use the existing engine's transactional
commit and cycle limit. Compound handlers follow the usual inner-before-outer priority.
Use `transitions { ... }` inside a compound for the full state handler API.

Factory-backed targets use `on<Action, Target>()`, or
`transition<Action, Target>(guard = { context, action -> ... }, reduce = { context, action -> ... })`.
`afterEnter<Target>(delay)` uses the target entry factory after a timer. The existing
`on<Action, Target> { context, action -> Target(...) }` and `after<Target>(delay) { ... }`
remain available for flat states without entry factories. Mixing both ways of supplying a
target value is rejected at definition time, rather than silently overwriting the supplied data.

These declarations also work inside `parallelStateMachine` regions. State names must remain
unique within each region, including nested compounds. The same generated graph drives the
runtime, invariants, Mermaid, diagnostics and recordings. Missing targets, missing history,
duplicate names and hierarchical leaves without entry factories fail during construction.
See [executable hierarchy tests](src/commonTest/kotlin/actron/statechart/machine/TypedHierarchyDslTest.kt).

### Nested typed parallel states

A compound can contain `parallel(Phase.Loading) { ... }`. The outer state binding holds
`Phase.Loading`, while each region binds its own independent property in the same context:

```kotlin
compound<Phase.Active>(initial = Phase.Loading) {
    history(deep = true)
    on<Action.Pause, Phase.Paused>()
    parallel(Phase.Loading) {
        onDone<Phase.Done>()
        region<CurrentRegion, RequestState>(RequestState.Loading,
            readState = { it.current }, writeState = { c, state -> c.copy(current = state) },
        ) {
            state(RequestState.Loading) { on<Action.CurrentLoaded, RequestState.Done>() }
            final(RequestState.Done)
        }
        region<ForecastRegion, RequestState>(RequestState.Loading,
            readState = { it.forecast }, writeState = { c, state -> c.copy(forecast = state) },
        ) {
            state(RequestState.Loading) { on<Action.ForecastLoaded, RequestState.Done>() }
            final(RequestState.Done)
        }
    }
    final(Phase.Done)
}
```

Region marker types supply namespaces without string keys. The outer value is written before
region entries; inactive regions retain data without being required to have active graph nodes.
Each region can itself contain compounds and parallel states. Completion waits for every region;
leaving the parent cancels their work. Deep history restores region configurations, while shallow
history re-enters their initial configurations. Business data remains current in both cases.

### State-local typed invocation

`typedInvokedMachine` builds a typed parent plus one scoped child using `InvokedMachine`:

```kotlin
typedInvokedMachine(
    id = DefinitionId("location"), version = DefinitionVersion("1"),
    initial = Phase.Idle, readState = { c: Context -> c.phase },
    writeState = { c, phase -> c.copy(phase = phase) }, child = locationMachine,
) { child ->
    state(Phase.Idle) { on<Action.Open, Phase.Locating>() }
    state(Phase.Locating) {
        on<Action.Close, Phase.Idle>()
        invoke(child,
            context = { parent -> LocationContext(parent.context.city) },
            toParent = { event, send ->
                when (event) { is LocationEvent.Found -> send(Action.Close) }
            },
        )
    }
}
```

The child handle is typed and must be invoked in exactly one owner state. No owner ID, string
routing key or action cast is required. The DSL returns a pure `InvokedMachine`; run it with
`InvokedMachineStore(composition, context, parentHandler, childHandler, scope)` and call `start()`.

The executor implements the core `Store` interface and owns one ordered mailbox and one
`StateFlow<InvocationSnapshot>`. Start explicitly or dispatch; collecting alone does not start
work. Use `admit(action)` when the caller needs an accepted/closed result. Concurrent
parent actions, owner-qualified child actions, command results and timers enter that mailbox.
It commits an entire composite decision before starting IO, preserves lane policies, cancels
child work on exit/reentry, and rejects stale child inputs. Feedback routes settle atomically:
commands from transient states that disappeared during routing never start. A failed child
start rolls back the parent and is reported through `onError`.

Use `dispatch(action)` for parent input and `dispatchChild(owner, action)` for input from a
specific child instance. `dispatchAndAwait` waits for a parent action to be decided, not for its
IO to finish. `checkpoint()` captures both sides at one message boundary and clock instant.
Inputs receive logical time when decided. The mailbox is unbounded; close may discard accepted inputs. The host owns the supplied scope
and any dedicated dispatcher. `coroutineContext` selects sequential decision processing;
commands use `scope`. Dispatcher pools serialize decisions without pinning a physical thread.
UI collectors choose their own UI context. `Dispatchers.Unconfined` is rejected for command
execution. `close()` cancels commands, timers, recovery waits and pending checkpoint requests.

`resumeInvokedMachineStore` restores an unstarted executor from an `InvocationCheckpoint`.
It retains context, ownership, lanes and remaining timer delays without repeating entry hooks.
`parentRecovery` and `childRecovery` select how previously running commands resume. Child events
route internally through the composition; parent effects are exposed as `event`/`mailbox`.
There is one child per composition, matching the existing invocation DSL contract.
For versioned serialization and `save(store)` / `resume(saved, ...)`, see
[invocation persistence](../actron-timetravel/README.md#invoked-parent-and-child).

## State-local machine DSL

For flat machines, `stateMachine` declares the graph and implementations together:

```kotlin
val machine = stateMachine<Context, Act, Command, Ev>(id, version, initial = idle) {
    state(idle) {
        on<Act.Submit>(loading,
            guard = { context, action -> action.query.isNotBlank() },
            reduce = { context, action -> context.copy(query = action.query) },
        )
    }
    state(loading) {
        onEnter { command(Command.Fetch(context.query)) }
        on<Act.Loaded>(done) { context, action -> context.copy(items = action.items) }
        internal<Act.Rename> { context, action -> context.copy(title = action.title) }
        after(8.seconds, error) { context -> context.copy(message = "Timed out") }
        onExit { /* pure cleanup; command cancellation is automatic */ }
    }
    state(done) {}
    state(error) {}
}
```

`on<T>` infers the action type in guards and reducers, and restricts T to the machine's
action type. No application casts or guard/effect keys are needed. Transitions are considered
in declaration order. `internal<T>` is an internal self-transition; it preserves timers and
command ownership. External self-transitions restart them. Hooks and reducers are pure;
`onEnter` describes commands, and the separate `CommandHandler` executes IO.

The facade builds the existing `Machine`/`StateChartDefinition`, retaining validation,
recording and replay semantics. Implementation keys are generated deterministically from
transition declaration indices. Bump `DefinitionVersion` when changing the definition.
The typed hierarchy and parallel builders use that same model and runtime.

## Automatic workflow transitions and typed DSL

The lower-level `stateChart` DSL builds the same immutable `StateChartDefinition` used by
runtime, Mermaid, validation and tests. This API uses explicit `GuardKey`/`EffectKey` labels;
the recommended `typedStateMachine` DSL binds implementations inside each state instead:

```kotlin
val valid = GuardKey("valid")
val update = EffectKey("update")
val chart = stateChart(idle) {
    state(idle) { on<Act.Submit>("Submit", validating) }
    state(validating) { always(done) { guard(valid); effect(update) } }
    final(done)
}
val machine = Machine<Context, Act, Command, Ev>(id, version, chart) {
    guard(valid) { snapshot, _ -> snapshot.context.isValid }
    effect(update) { context, _ -> context.copy(submitted = true) }
    invariant("submitted-is-valid") { !it.context.submitted || it.context.isValid }
    maxMicrosteps(100)
}
```

`compound(id, initial) { ... }` and `parallel(id) { ... }` nest state declarations; parent
transitions use `transitions(id) { onDone(target) }`. A compound completes when its active child
is final; a parallel completes when every region completes. Eventless transitions run first,
then completion transitions, until stable. `Machine` commits a macrostep
once; pure runtime users drive individual `automaticStep` calls. Guards see updated context
between microsteps. A limit overflow fails and rolls back the entire Machine decision.

`Transition(..., kind = TransitionKind.Internal)` requires source == target and runs effects
without exit/re-entry, preserving activations, commands and timers. A fired internal timer is
spent and does not restart. Ordinary external self-transitions still restart lifetimes.

## Decision explanations

`machine.decideExplained(snapshot, input)` decides once and returns the actual decision plus
active nodes, matching candidates, guard evaluations and the action-handler node. A candidate
can be selected, guard-rejected, priority-skipped, conflict-lost or guard-failed. Unvisited guards
are never invoked for diagnostics. `decision.outcome` also distinguishes stale commands/timers,
not-started inputs and already-started inputs. Explanation text contains metadata only.

For live `MachineStore`, pass `decisionDiagnostics { inputId, explanation -> ... }` as an
observer. This captures the actual decision; do not decide the input again to diagnose it.
`CausalityTracker(storeId, capacity)` links command outputs to the input that registered them,
exposes a `StateFlow`, and marks evicted ancestry incomplete. Group bridge causality remains
in the journal's cross-store message references. Production journal payload policy still defaults
to metadata only; application projections/codecs own redaction before recording payloads.

`whyNot(snapshot, action)` validates structure and explains selection eligibility without
reducers or IO. `whyNot(explained)` and `whyThisTransition(explained, transitionId)` reuse
the actual observations. Selection during an attempted automatic macrostep is distinct from
a committed transition; a later failure can roll it back.

For bounded command/timer/bridge causality shared across Stores, use the optional
[actron-diagnostics](../actron-diagnostics/README.md) hub and platform SDK sinks.

## Durable workflow and migrations

`DurableMachine(machine, storage, keyOf)` wraps the pure machine with an atomic snapshot/outbox
transaction. Storage implements `DurableMachineStorage.load/commit`, with durable compare-and-swap
by generation. `initialize`, `commit` and `executeNext` return only persisted intents. A durable
handler receives a stable `IdempotencyKey` and persisted attempt count; it must use service-side
idempotency or a transactional local receipt. Scope exit cancels ordinary work while durable
operations stay in the outbox. Completed receipts suppress repeat execution and reject payload
changes for a reused key. Use this executor instead of a second writer attached to MachineStore.

Recovery restores deadlines, outbox and ordinary command payloads. `dueTimers(now)` lists overdue
timers; `initialize` on an existing checkpoint returns ordinary registrations to restart. Supply
logical time that continues from the checkpoint rather than resetting on process restart.
Persisted outbox schemas and receipt retention remain application storage responsibilities.

`checkpoint.recoveryContract()` reports cancel-on-exit registrations, durable idempotent
operations, completed receipts, timers and persisted effects. Ordinary command repeat safety
is unknown; durable retry assumes the application's business idempotency contract.

`SnapshotMigration<OldContext, NewContext>` changes model version, configuration/context and
work explicitly. `newMachine.migrateSnapshot(oldSnapshot, migration)` validates the result's
hierarchy/history, activation ids, commands, timers, counters and invariants. `SnapshotMigrations`
provides an unambiguous acyclic version chain. `oldChart.diffTo(newChart)` produces a behavioural
PR diff and conservative snapshot/replay compatibility flags; it cannot inspect guard/reducer code.

## Scoped child machines

`InvokedMachine(parent, ownerNode, child, context, toChild, toParent)` is a pure typed composition.
Its snapshot holds both machines and the exact parent activation owning the child. Child inputs
carry that owner id, so late inputs cannot reach a replacement child after re-entry. Routing drains
in FIFO order within an atomic composite decision, with a feedback limit. Failure rolls back both
snapshots. Execute `parentCommands/parentTimers` and `childCommands/childTimers` after committing;
child work is addressed by owner plus its local command/timer id. Raw decisions describe the
microsteps; work that started and stopped in the same composite decision is filtered out.

Use `MachineGroup` for independently executing/persisted stores and failure isolation; its
consistent checkpoint and bridge contracts remain unchanged. See the semantics documents under
`doc/internal/design/2026-10-06-*.md` for transaction and lifecycle details.

## Behavioural queries and reachability

`chart.query(TransitionQuery(sources = { it == node }, includeDescendants = true))` returns
matching declaration rows without running guards or effects. `chart.impactTo(nextChart)`
reports conservative affected states, transition identities and referenced guard/effect labels.

`chart.analyzeReachability(maxConfigurations, maxEdges)` explores structural configurations
and history, with dead ends separated from terminal configurations. Guards, context and IO
are opaque. A truncated search reports an empty `structurallyUnreachable` set and `truncated = true`; unvisited
states are not proof of impossibility. See the [five-wave contracts](../doc/internal/design/2026-10-06-competitive-roadmap.md)
for payload-based verification, exported runtime frames, IDE navigation and CI review.

Nullable APIs and optional containers have been removed from library implementations.
See the [null-free API policy](../doc/guides/absence-policy.md) for breaking replacements
and the strict CI checks, including inferred types. Only current persistence formats are supported.


## Continuing a checkpoint

The `resumeMachineStore` factory creates a new executor from a started
`ExecutorCheckpoint`. It preserves active configuration and context without rerunning entry
hooks, restarts running commands, retains queued lanes and schedules remaining timer delays.
Call `start()` on the returned store. Resume validates configuration/history, activations,
command scopes, timer references, counters and user invariants before creating runtime work.
For an invocation this applies to the parent and its active child.

The checkpoint is the only restore source. A `StateSaver` supplied through the builder or a
pre-start patch still saves new commits; its `restore` callback is not called. An `initialState`
patch also cannot replace the resumed snapshot. Ordinary `MachineStore` creation retains the
usual saver restoration contract.

When replacing a live executor, close the old one and
discard stale callbacks before installing the replacement.

Commands have **at-least-once** semantics on restart: use restartable handlers, such as read-only
queries. This does not reconstruct accepted mailbox inputs or replay transient effects.
Checkpoints with ending commands or unacknowledged effects are rejected. Definition/version
and machine invariants are checked. Application navigation is restored by the host.


### Choosing how a pending command resumes

`resumeMachineStore(..., recovery = strategy)` and the invocation executor support
`CommandRecoveryStrategy<CMD, A>`. Return `CommandRecoveryDecision.Restart` to repeat the original
handler, `Reconcile(handler)` to execute a status/reconciliation handler instead, or
`AwaitDecision { envelope -> ... }` to suspend for an application decision. Only previously
running commands use the strategy; queued and new commands execute normally. An approval wait
keeps its lane occupied and is cancelled with its owner. On another save/restore the application
strategy runs again. Handlers still produce actions, never state writes. Selection, waiting and
reconciliation errors follow normal command failure handling. Policies are code and are not
serialized. External repeat safety remains an application/service contract, not an exactly-once
guarantee supplied by the coroutine runtime.

## Automatic graph diagnostics

`chart.diagnostics` lazily caches a structural report for that definition. The inspector reads
it automatically. `chart.diagnose(maxConfigurations = 1_000, maxEdges = 10_000)` runs an
explicit bounded analysis for tooling/tests. Neither API evaluates application guards,
reducers, command handlers or action payloads.

Diagnostics carry a typed code, severity, state identities and exact transition indices.
Invalid endpoints, duplicate state IDs, invalid hierarchy/history and invalid transition
sources/delays are errors; malformed graphs are not explored. Structurally unreachable
states, reachable non-final leaves without an exit and transitions shadowed by an earlier
unconditional transition with the same source/trigger are warnings. Ancestor exits count;
final leaves are intentional terminals. Separate timer identities are not treated as
shadowed just because their delays match.

The search assumes opaque guards can succeed. On budget exhaustion it reports incomplete
analysis and never labels unseen states as proven unreachable. A warning does not prove a
bug (for example a non-final terminal can be intentional). This adds inspection metadata;
existing execution validation remains responsible for rejecting invalid runtime models.

## Runtime profiling

`MachineProfiler` observes queue delay, input processing, state residence and command
handler lifetime separately. Attach its observer, `profileWith(profiler)` Store configuration,
and wrapped command handler. Both `MachineStore` and `resumeMachineStore` support this setup.
It retains bounded correlations and immutable aggregates, without action/command payloads.
See [measurement semantics](../doc/guides/temporal-schedules-and-profiling.md#4-profile-the-running-machine).
