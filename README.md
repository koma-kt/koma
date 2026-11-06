# Actron

![Actron — actions and states turn tangled behavior into an ordered graph.](doc/assets/actron-chaos-to-order.png)

**Kotlin Multiplatform MVI with typed state machines.**

Describe states, actions and transitions in Kotlin. Actron processes actions in order,
publishes state, and runs commands such as network requests. The same machine definition
supports diagrams, tests, recordings and replay.

Start with three parts:

| Part | Responsibility |
|---|---|
| `Machine` | State-local action handlers and declarations of state-owned entry work |
| `MachineStore` | Queued execution, observable snapshots and command/timer lifetimes |
| `CommandHandler` | I/O; returns actions to the machine |

The UI sends actions and reads `snapshot.context`. Only machine decisions change state.
A command handler never writes state directly.

[Quick start](#1-a-store-for-a-counter) · [Installation](#installation) ·
[Requests](#requests-and-results) · [Weather demo](#see-the-state-behind-the-screen) ·
[Agent skills](#skills-for-your-ai) · [Modules](#modules)

Actron is useful when cancellation, retries, timers or concurrent workflows are part of the
feature's behavior. A counter can use one state; a loading screen can use sealed phases;
more complex workflows can add hierarchy or parallelism. Navigation, repositories and DI
remain application concerns.

**Total Null Safe by design.** Actron's domain values and API contracts are non-null.
Lifecycle phases and domain outcomes model absence explicitly. Source and compiler checks
enforce this policy across the library, examples, tests and tooling in CI. Nullable platform
and dependency APIs are normalized at their boundaries; see the
[null-free API and enforcement policy](doc/guides/absence-policy.md) for the exact scope.

## 1. A Store for a counter

`behaviorStateMachine` declares handlers inside their state. Read the immutable `state` and
propose its replacement with `target`. No separate node IDs or reducer registration
strings are needed.

```kotlin
import actron.core.Action
import actron.statechart.machine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

data class CounterState(val count: Int = 0)
sealed interface CounterAction : Action {
    data object Increment : CounterAction
    data object Decrement : CounterAction
}

val counterMachine = behaviorStateMachine<CounterState, CounterAction, Nothing, Nothing>(
    id = DefinitionId("counter"),
    version = DefinitionVersion("1"),
    initial = CounterState(),
) {
    state<CounterState> {
        on<CounterAction.Increment> {
            target(state = state.copy(count = state.count + 1))
        }
        on<CounterAction.Decrement> {
            target(state = state.copy(count = (state.count - 1).coerceAtLeast(0)))
        }
    }
}

fun counterStore(scope: CoroutineScope) = MachineStore(
    machine = counterMachine,
    context = CounterState(),
    handler = CommandHandler<Nothing, CounterAction> { _, _ -> },
    scope = scope,
    coroutineContext = Dispatchers.Main, // Choose where decisions and commits run.
)
```

Create the Store once for its owner's lifetime. `store.dispatch(CounterAction.Increment)`
queues an action and starts a lazy store; it does not wait for the new state. Collect
`store.state` and render `snapshot.context.count`. Call `store.close()` when the owner is
destroyed. `Nothing` means this machine has no commands or events.

For Compose, import `actron.compose.collectAsUiState` and `androidx.compose.runtime.getValue`.
Inside a composable, `val snapshot by store.state.collectAsUiState()` collects on Main even
if the machine processes on a background dispatcher. The platform must provide
`Dispatchers.Main` and create its composition on the UI thread. This is independent of
`scope`, which supplies the command execution context and lifetime.

The queue preserves acceptance order per machine. A single-thread dispatcher or Main also
pins processing to one physical thread; Default/IO pools still process inputs sequentially,
but may use different threads. See the [full threading contract](doc/guides/machine-mvi.md).

The Store engine has no public construction DSL. For retained components, use
[`retainedMachine`](actron-decompose/README.md).

## Requests and results

Declare a command in the state's suspend `onEnter` block. Execute it in a `commandHandler`, returning
a success or failure action. Leaving the owning state cancels its commands; results from
commands that are no longer registered are rejected by the runtime.

For example, using the domain types from the
[Weather search machine](examples/weather/shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt):

```kotlin
fun citySearchHandler(
    searchCities: suspend (String) -> List<CityOption>,
): CommandHandler<SearchCitiesCommand, CitySearchAction> = commandHandler(
    onFailure = { request, _ ->
        CitySearchAction.Failed(request.revision, "Could not load cities. Try again.")
    },
) { request ->
    CitySearchAction.Found(request.revision, searchCities(request.query))
}
```

Pass this handler to `MachineStore`. `onFailure` maps service errors to domain actions;
coroutine cancellation is rethrown. For several command types, use
`commandHandlers { handle<CommandType>(onFailure = ...) { ... } }`.
For streams with multiple results, use the lower-level `CommandHandler` callback.
In the behavior DSL, `on<Action>` is synchronous and transactional; `onEnter` runs outside
that transaction and may suspend. Use ordinary Kotlin conditions and `delay`. Entry work
belongs to the state activation and is cancelled on exit. `state` stays the snapshot captured
at handler entry; `target` does not mutate it. Even a same-state transition exits and
re-enters, restarting entry work.

See the [behavior DSL contract](actron-statechart/README.md#state-local-kotlin-behavior)
and [request and threading contract](doc/guides/machine-mvi.md).

## Component lifetime

With Decompose, `actron-decompose` creates, retains and starts the Store in one call:

```kotlin
import actron.decompose.retainedMachine

// Using counterMachine from the quick start:
val store = componentContext.retainedMachine(
    key = "counter",
    machine = counterMachine,
    initial = CounterState(),
    handler = CommandHandler<Nothing, CounterAction> { _, _ -> },
)
```

The InstanceKeeper owns the Store and its command scope. Component recreation reuses them;
keeper destruction closes them. Handlers must not capture the old component or its lifecycle
scope. Recording and checkpoint restoration are optional parameters of the same call.
See [Decompose integration](actron-decompose/README.md).

## 2. Which kind of state machine do you need?

A **state machine** says which transitions are allowed. A **statechart** extends that idea
with hierarchy, parallel regions and history. Choose the smallest model that explains your
feature's behaviour.

| Shape | Example | Actron representation |
|---|---|---|
| One state with changing data | A counter or an editable form | `behaviorStateMachine`, `on<Action>` and `target` |
| Flat, mutually exclusive phases | Idle → Loading → Content or Error | `typedStateMachine`; `on<Action> { target { ... } }` declares each graph edge |
| Hierarchical phases | Connected contains Idle and ChatOpen | `compound<Parent>`; one active child at a time |
| Parallel phases | Connection status and upload status evolve independently | `parallelStateMachine` regions, or nested `parallel<Parent>` |
| Return to a previous substate | Resume an interrupted multi-step editor | `history()` / `history(deep = true)` and `onHistory<Action, Parent>()` |
| Timed work | Debounce or request timeout | Suspend `onEnter` with coroutine delay/timeout; explicit charts also offer `after(delay) { target { ... } }` |

Keep changing data such as queries and counters in context fields. Use distinct states when
allowed actions or work lifetimes change. The typed DSL derives graph IDs from state types;
there is no separate phase-to-ID map. See the [typed DSL guide](actron-statechart/README.md#state-types-shared-by-graph-ui-and-recordings)
for explicit graph declarations, shared context bindings, hierarchy and history.
`typedStateMachine` remains useful when static transition structure is part of the model;
behavior handlers use ordinary Kotlin and expose a conservative graph of possible targets.

Declare a destination once, with an optional guard:

```kotlin
state<CitySearchState.Loading> {
    on<CitySearchAction.Found> {
        guard { action -> action.revision == state.revision }
        target { action -> CitySearchState.Success(state.query, state.revision, action.results) }
    }
    after(8.seconds) {
        target { CitySearchState.Error(state.query, state.revision, RequestFailure.Timeout) }
    }
}
```

The return type of `target` supplies the graph destination. Its value is the entire next state.
Only parallel regions bind an individual field through `readState` and `writeState`.
See the [transition guide](actron-statechart/README.md#declaring-a-transition-with-one-target-type)
and the complete [city search machine](examples/weather/shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt).

### Several machines on one screen

Use parallel regions when independent phases belong to **one machine and snapshot**.
Use separate machines with `MachineGroup` when each feature owns its own state, actions and
commands. Typed routes translate an event from one member into an action for another.
Each machine keeps its own queue; the group does not make their state commits atomic.

The current [Weather composition](examples/weather/shared/src/commonMain/kotlin/actron/example/weather/app/WeatherMachines.kt)
uses a screen machine and three independent machines for current weather, hourly forecast
and advice. The screen emits refresh events; routes deliver each feature's refresh action.
The UI combines their snapshots without introducing another writable owner of feature state.
Use [typed invocation](actron-statechart/README.md#state-local-typed-invocation) when a parent
must own a child workflow in one atomic snapshot.


## 3. A Machine makes decisions; I/O runs afterward

A StateChart `Machine` defines behavior; `MachineStore` executes it and exposes `MachineSnapshot<C>`.

The decision engine takes a snapshot and an input and returns a `Decision`. Commands, timer
changes and effects are data in that decision. `MachineStore` commits it, then a
`CommandHandler` performs I/O and sends results back as new inputs.

The counter above can also run without coroutines:

```kotlin
val started = counterMachine.decide(
    counterMachine.initialSnapshot(CounterState()), MachineInput.Start(MachineTime.Zero),
)
val incremented = counterMachine.decide(
    started.snapshot, MachineInput.Dispatch(CounterAction.Increment, MachineTime.Zero),
)
// incremented.snapshot.context.count == 1; no Store or I/O was started.
```

`decide` does not execute suspend entry work. `MachineStore` runs that work and queues its
outcomes. Replay applies recorded outcomes without repeating I/O. It requires compatible
machine definitions/codecs and pure action rules; keep external reads and randomness out of
those rules. A behavior graph is a conservative envelope, not proof that every edge is reachable.

The typed DSL supports [sealed phases](actron-statechart/README.md#state-types-shared-by-graph-ui-and-recordings),
[parallel regions](actron-statechart/README.md#typed-parallel-regions),
[hierarchy, history and completion](actron-statechart/README.md#typed-hierarchy-history-and-completion),
and [child invocation](actron-statechart/README.md#state-local-typed-invocation).
Explicit-ID definitions remain available for lower-level tools and existing users.

## See the state behind the screen

The [Weather example](examples/weather/README.md) puts the application and Actron Inspector
side by side. Enable **Debug panel** in Settings before searching for **Rome, Italy**.

![Weather search results for Rome, Italy beside the event timeline and the Found action, showing State Before: Loading and State After: Success.](doc/assets/weather-inspector-event.png)

The application is on the left, the event timeline in the middle, and the selected decision
on the right. Inspect the action payload and before/after state, search or bookmark events,
and step through recorded snapshots. **Live** returns to the running application.

![Weather search graph with active state, timers and transition coverage.](doc/assets/weather-inspector-graph.png)

**Graph** shows the same machine definition used by the executor, including timers and
transition coverage. Pan, zoom, fit the diagram and select states or transitions for details.
The inspector is reusable through [actron-timetravel-compose](actron-timetravel-compose/README.md).

## 4. Time Travel from a real saved run

![Search Rome, inspect earlier events, then continue execution from a saved checkpoint.](doc/assets/weather-time-travel.gif)

Viewing history renders saved frames without repeating network calls. **Continue here**
restores a compatible checkpoint and starts a new continuation. City search retains the remaining
debounce and timeout duration; an unfinished HTTP request starts again. Other Weather features
restart unfinished behavior entry work from its beginning. Later history is replaced. The host restores
navigation. **Export** shares a recording; **Import** opens it in view-only mode.
See [Weather's restoration and replay contract](examples/weather/README.md#session-history-and-replay).

```sh
./gradlew :weather:desktopApp:run
```

## Skills for your AI

Start agents with [skills/README.md](skills/README.md). The repository provides three focused
skills: [modeling and integration](skills/actron-model/SKILL.md),
[testing](skills/actron-test/SKILL.md), and [inspection/replay](skills/actron-inspect/SKILL.md).
Ask the agent to read the relevant `SKILL.md`; installation is optional.

For work inside this repository, read [AGENTS.md](AGENTS.md) first. For application code,
verify the resolved Actron version and use its matching signatures and examples.
`StoreBuilder` and `createRuntimeStore` are internal; application reducers use the StateChart
`Machine`. The former standalone Store DSL is unsupported.

## Installation

Actron's configured coordinates are **`io.github.roman-n1:<module>:5.0.0-alpha.1`**.
GitHub releases provide source archives and may include an IDE plugin ZIP. Maven Central
publication is separate; for development, use a local checkout/composite build:

```kotlin
// settings.gradle.kts in your application
includeBuild("../actron") // path to this repository; its root name is Actron
```

```kotlin
// Kotlin Multiplatform build.gradle.kts in your application
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:actron-statechart:5.0.0-alpha.1")
            // For Decompose: implementation("io.github.roman-n1:actron-decompose:5.0.0-alpha.1")
        }
    }
}
```

The publishing convention sets the included projects' group/version, so Gradle substitutes
the matching coordinates. Add only the modules you need; no Actron opt-in is required.
Keep all Actron modules on the same version. Actron uses `actron.*` packages and requires
recompiling consumers that previously imported `koma.*`; it is not a binary replacement.
For an Android-only build, the same dependency line belongs in `dependencies { … }`.

## Modules

Every library module has its own README with setup, examples and its contract.

| Module | Purpose |
|---|---|
| [actron-core](actron-core/README.md) | Internal execution engine and shared action/event/lifetime contracts |
| [actron-decompose](actron-decompose/README.md) | One-call retained machine setup and lifecycle-bound observation for Decompose |
| [actron-compose](actron-compose/README.md) | `ViewStore`, state rendering, derived UI values and transient event collection |
| [actron-message](actron-message/README.md) | Transient messages from command handlers and runtime subscriptions |
| [actron-logging](actron-logging/README.md) | Store logging and structured journal output |
| [actron-test](actron-test/README.md) | Await processing, record states/events and diagnose handlers |
| [actron-statechart](actron-statechart/README.md) | Declarative model, validation, diagrams, chart runtime and pure Machines |
| [actron-statechart-compose](actron-statechart-compose/README.md) | Retained Machine effects delivered to Compose |
| [actron-statechart-test](actron-statechart-test/README.md) | Scripted/generated scenarios, data and rules DSL, virtual time and coverage |
| [actron-timetravel-test](actron-timetravel-test/README.md) | Optional export of generated test traces as view-only recordings |
| [actron-diagnostics](actron-diagnostics/README.md) | KMP causal traces for decisions, commands, timers and bridge effects |
| [actron-diagnostics-sdk](actron-diagnostics-sdk/README.md) | Optional OpenTelemetry and Sentry adapters for JVM/Android |
| [actron-diagnostics-crashlytics](actron-diagnostics-crashlytics/README.md) | Optional Android Crashlytics adapter |
| [actron-observability](actron-observability/README.md) | Ordered, bounded diagnostic journal and file storage |
| [actron-persistence](actron-persistence/README.md) | Current checkpoint codecs and restoration without debug tooling |
| [actron-timetravel](actron-timetravel/README.md) | Recording, codecs, checkpoints, replay and isolated branches |
| [actron-timetravel-compose](actron-timetravel-compose/README.md) | Inspector UI and group replay/experiment controls |
| [weather](examples/weather/README.md) | Compose Multiplatform MVI weather app with a live API and resizable debug panel; not a published library |

## Project and compatibility

Actron is the independent [roman-n1/actron](https://github.com/roman-n1/actron) fork of
[koma-kt/koma](https://github.com/koma-kt/koma) 4.0.0. Artifacts use `io.github.roman-n1`
and packages use `actron.*`. Existing Koma consumers must migrate and recompile;
source, binary and runtime interoperability with upstream Koma is unsupported.

See [migration from Koma](doc/guides/migrating-from-koma.md), the
[divergence inventory](doc/internal/design/2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400),
and the [unreleased API baseline](doc/internal/adr/2026-10-26-unreleased-api-baseline.md).
Library and example sources follow the [absence policy](doc/guides/absence-policy.md).

## Contributing and deeper reading

- [Kotlin code style](doc/guides/code-style.md): `./gradlew ktlintCheck` to verify, `./scripts/format-kotlin.sh` to format the entire repository.

- [Machine MVI guide](doc/guides/machine-mvi.md): decisions, commands, lifetime and execution policies.
- [Absence policy](doc/guides/absence-policy.md): migration away from null and optional containers, API changes and the CI guard.
- [Device resource budgets](verification/resource-soak/README.md): required Android/iOS soak runs, resource cleanup, memory plateau and commit latency reports.
- [Internal documentation](doc/internal/README.md): architecture, decisions, review evidence and roadmap.
- [IntelliJ plugin](tooling/intellij-plugin/README.md): K2 gutter, model Find Usages and diagram navigation.
- [Build conventions](build-logic/README.md): Maven Central release setup, BOM version alignment and CI cost controls.
- Validation: `./gradlew jvmTest apiCheck checkDebugGraph`; the CI also tests Android, iOS, JS and Wasm.
- Published artifacts: `scripts/verify-published-consumer.sh jvm` builds an
  [independent Maven consumer](verification/published-consumer/README.md); CI also compiles its
  Android, JS, Wasm and iOS variants.

Published-consumer checks reject upstream Koma dependencies in Actron's dependency graphs.
