# Weather application

A two-screen Compose Multiplatform application for Android, Desktop, iOS and the browser.
Search for a city, open its current weather, six-hour forecast and dismissible weather advice, refresh or go back.
Settings contains an optional debug history panel, shown only on the right. Recording starts
with the machines, independently of panel visibility.

## Read the example

Start with [How Weather uses Actron](docs/architecture.md): the path from UI intent to machine
decision and HTTP result, the typed search DSL, independent weather machines, component lifetime,
and the distinction between preview and restoration.

For tests, read [the test-source README](debugTools/src/commonTest/README.md). It maps the scripted,
generated and HTTP-cancellation examples to the library APIs and shows how to inspect a
generated scenario in the running application. Source comments mark the integration points;
the guides explain the contracts behind those calls.

Suggested source order:

1. [CitySearchMachine](shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt):
   context, actions, command data, transitions, debounce and cancellation.
2. [CitySearchEffects](shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchEffects.kt)
   and [CitySearchComponent](shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchComponent.kt):
   call the feature repository, dispatch actions and collect state.
3. [CityWeatherMachine](shared/src/commonMain/kotlin/actron/example/weather/feature/weather/presentation/CityWeatherMachine.kt):
   independent regions in one machine.
4. [MachineInstance](../../actron-decompose/src/commonMain/kotlin/actron/decompose/MachineInstance.kt)
   and [WeatherRootComponent](shared/src/commonMain/kotlin/actron/example/weather/app/WeatherRootComponent.kt):
   connect Actron execution to Decompose lifetime and navigation.
5. [GeneratedWeatherScenarios](debugTools/src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt):
   describe test data and properties, then let the graph explorer discover sequences.

## Run

```sh
./gradlew :weather:desktopApp:run
./gradlew :weather:desktopApp:run --args='--offline'
./gradlew :weather:webApp:wasmJsBrowserDevelopmentRun
./gradlew :weather:webApp:jsBrowserDevelopmentRun
./gradlew :weather:androidApp:assembleDebug
```

The modules follow the same separation as `MyApplication`:

```text
examples/weather/
  shared/       Production Compose UI, machines, repositories and current checkpoints
  debugTools/   Optional recorder, history UI and platform file dialogs
  androidApp/   Android Activity, manifest and device tests
  desktopApp/   JVM entry point and desktop runtime
  webApp/       Shared browser entry point, HTML, JavaScript and Wasm executables
  iosApp/       SwiftUI host, document pickers and Xcode project
```

The example uses Actron's existing Kotlin, Compose and Android plugin versions.
Android, Desktop, JavaScript, WebAssembly and iOS (arm64 device/simulator) share the same UI
and feature machines. The iOS host targets iOS 18.5+ to match the linked Skiko/ICU runtime.
Run Gradle commands from the Actron repository root.

For iOS, open `examples/weather/iosApp/iosApp.xcodeproj`, select the `iosApp` scheme and an
arm64 simulator, then Run. The Xcode build phase compiles and embeds `Shared.framework`.
Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` for a physical device; simulator builds
do not require a signing team. Kotlin follows the application's foreground/background lifecycle;
Swift owns native document import/export. Live data uses Ktor's Darwin engine on iOS.

Web's `webMain` entry point and resources are shared by JS and Wasm. Use the Wasm run task
for modern browsers, or the JS task for the existing JavaScript target. Both retain recording
import/export through the browser's file picker and downloads.

In Android Studio select the `weather:androidApp` application module. The debug APK is
`examples/weather/androidApp/build/outputs/apk/debug/androidApp-debug.apk` (Android 7.0+).
Live data uses Open-Meteo without an API key. `--offline` selects explicit local fixtures on
Desktop. The old article, replay and CLI-check modes have been removed from this application.

## Responsibilities

- **Decompose** owns the component tree, lifecycle and navigation stack. The root handles only
  `CitySelected(city)` and `BackClicked`. The weather configuration carries the selected city.
- **Actron** owns each feature's `MachineStore`, state, transitions, timers, command execution
  and cancellation. Search and weather have independent stores; weather has a screen parent and three independent child stores.
- **Command handlers** call feature repositories and return typed result actions. Network
  access is outside the pure machine definitions.
- **Compose** reads the machine snapshot and sends screen intents. UI models are pure formatting
  projections, never independent mutable stores.

The root does not copy feature states or forward search/refresh requests. Each feature handles
its own intents. The KMP-style parent/child bridge carries navigation news only. No parent-to-child
state broadcasts are needed; the corresponding action type is `Nothing`.

```text
app/WeatherRootComponent.kt       Decompose Search / Weather(city) stack
app/WeatherComponentFactories.kt  Retained feature Store assembly
app/session/                     Current checkpoints and process restoration
debugTools/app/debug/            Optional recording, preview and platform file adapters
core/component/                  Base/StackComponent and bridges
core/mvi/RequestState.kt          Shared typed request lifecycle
feature/cities/
  data/                          HTTP and offline search repositories
  domain/                        CitySearchRepository
  presentation/                  Component, machine, command handler, screen and UI projection
feature/weather/
  data/                          Current/hourly HTTP and offline repositories
  domain/                        Weather data and repository interfaces
  presentation/                  Parent machine, screen and derived UI projection
currentweather/presentation/     Standalone current-weather context, actions, machine and handler
hourlyforecast/presentation/     Standalone forecast context, actions, machine and handler
weatheradvice/presentation/      Standalone advice context, actions, machine and handler
```

Application component factories retain each `MachineInstance` through Decompose's `InstanceKeeper`.
Feature components receive the Store; they do not know about recording or checkpoint restoration.
Recreating the host with its saved navigation state reuses the same executor. Returning from
weather destroys that child and cancels its requests. Search stays in the back stack with its
query and results. Opening another city creates a new weather child and executor. Merely opening
search does not request weather for a default city.

Platform entry points select the ordinary or debug root and call `root.Render()`.
Desktop constructs/destroys it on the Swing UI thread; Android uses the Activity component context.
Each screen owns its padding and scrolling. There is no `WeatherApp` or `WeatherAppContent` wrapper.

## Concise machine setup

A normal machine needs one call to `actron-decompose`:

```kotlin
val store = componentContext.retainedMachine(
    key = "city-search",
    machine = CitySearchMachine.create(),
    initial = CitySearchState(),
    handler = citySearchHandler(repository),
)
```

Add `restoration = session.searchRestoration()` to restore/track search checkpoints.
Independently add `recording = history.recording()` to enable recording and profiling. The option binds once, before
startup. Weather retains four stores with typed MachineGroup routes and starts the parent after the children enter Ready; the parent emits the first refresh event automatically.
The adapter owns retention/start/close and the command scope; UI subscriptions still belong
to their component. See [the installation contract](docs/architecture.md#one-call-machine-installation).
The helper is part of `actron-decompose`; these changes have not yet been released to Maven Central.

## State machines

`CitySearchMachine` uses `RequestState<List<CityOption>>`:
`Idle → QueryChanged → Debouncing → Loading → Success / Error`.
Every edit enters `QueryChanged` and updates the query and revision once. Its guarded `always`
transitions select `Idle` for blank input or `Debouncing` otherwise, within the same decision.
350 ms of quiet starts the request, even for one letter.
Clearing cancels pending work and restores `Idle`. An empty server response is `Success(emptyList())`.
Responses for outdated revisions are ignored. Requests time out after eight seconds.

`CityWeatherMachine` owns the city header and accepts `WeatherScreenAction.Refresh`. Its Active
entry emits one `WeatherScreenEvent.RefreshChildren` on fresh startup and every Refresh self-loop.
`WeatherMachines` attaches four stores to a MachineGroup and declares three typed routes:

- RefreshChildren → `CurrentWeatherAction.Refresh` for CurrentWeatherMachine.
- RefreshChildren → `HourlyForecastAction.Refresh` for HourlyForecastMachine.
- RefreshChildren → `WeatherAdviceAction.Refresh` for WeatherAdviceMachine.

The children have their own contexts, action types, commands, handlers and serialized queues.
They know neither the parent nor one another. They start in Ready without requests or timers;
then the parent's first event starts loading automatically. Current and Hourly use
Ready / Loading / Success / Error. Advice uses Ready / Loading / Hidden / Visible / Error /
Dismissed and interprets its own reading as weather tips. A global update makes two independent
current-weather calls and one hourly call. Local Hourly Retry and Advice Retry/Dismiss preserve siblings.

Every child accepts its Refresh from every state, including Loading and Dismissed. Re-entering
Loading cancels the old work and starts a new request and timeout. Group delivery is not an atomic
multi-store transaction; the immutable UI projection may briefly show mixed phases. Saved and
continuable frames include all four checkpoints and require all routed messages to be settled.
Restoration preserves completed and dismissed children, restarting only pending work.

The state variants define graph identity through the DSL. There are no independent state IDs,
loading/error flags or string guards/effects. `RequestState` uses generated `@Serializable`
serialization. Strict repository null-safety checks apply to this example too.

## Data

Current weather includes temperature, apparent temperature and wind. The receipt timestamp is
shown at the upper right. The hourly request returns six hours of temperature, wind and rain
probability in the selected city's local time. Errors are shown without replacing live data
with fixtures. Weather data: Open-Meteo ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/));
city data: [GeoNames](https://www.geonames.org/).

## Verification

```sh
./gradlew :weather:shared:jvmTest :weather:debugTools:jvmTest
./gradlew :weather:shared:iosSimulatorArm64Test :weather:debugTools:iosSimulatorArm64Test
./gradlew :weather:webApp:jsBrowserDevelopmentWebpack :weather:webApp:wasmJsBrowserDevelopmentWebpack
./gradlew :weather:androidApp:assembleDebug :nullability-guard:checkPolicy
```

Tests cover HTTP decoding, debounce and revisions, cancellation and timeouts, independent
independent requests, typed routes, generic serialization, navigation, host recreation and request cleanup.
The optional debugTools module integrates the library's recording/replay and inspector APIs; its host adapter
owns only Weather display frames, screen restoration and platform file access.

## Session history and replay

Every debug feature executor attaches the library's `BoundedMachineRecorder` through a
`DecisionTraceObserver` before `start()`. The debug shell retains one `WeatherHistory` across host
recreation and supplies recording to both feature factories. `WeatherSession` independently owns
checkpoints, route and time; it has no UI hooks or diagnostic fields. Its timeline enforces **500 entries in
total**, including closed weather screens. Evicting a timeline entry also trims that feature's
recorder with `discardBefore`, advancing its replay checkpoint. Closed feature recorders are
released when their last timeline entry disappears; indices remain stable.

Open the gear next to **Cities**, then enable **Debug panel** for the reusable library `HistoryPanel`. The global
chronology contains only machine events and groups lifetimes by stable machine ID.
Navigation stays in the example: `WeatherFrame` carries the route. The debug-only
`WeatherHistoryFrame` adds frozen transition coverage. The debug shell unwraps the application
frame and asks the ordinary root to restore its Decompose stack. The library treats the combined
frame as an opaque snapshot. Each selection shows
the input action with its payload between the before/after active states, structured field
changes. Technical diagnostics remain in the recording model. Event and update timestamps
use the device's local time zone and locale.

Selecting an event immediately renders the saved application frame with inputs disabled, without calling any
repositories. Live recording continues in the background. Switching to **Live** exits preview.
**Continue here** replaces the Decompose stack and executors with the selected frame and exits preview. Search context and both independent weather machines are restored
together. Completed requests stay completed; running read requests restart, queued commands
keep their lanes. Restoring during search debounce starts a new 350 ms delay; no debounce
deadline is stored in the feature state. Old executors are closed; callbacks
from an earlier branch cannot modify the new frame. After successful restoration, entries after the selected event are removed. Earlier entries
and the selected event stay available; new execution extends this linear history. The original
running session is replaced. This is an at-least-once restart of read-only
work, not replay of arbitrary external side effects. Unsupported checkpoints with pending
effects or terminal command outcomes cannot be continued.

The example declares a typed `checkpointGroup` for search and optional weather checkpoints.
One `save(frame)` / `restore(text)` call handles all machines and compatibility checks.
Its host adapter maps the application route and retains branch metadata.
The root registers a versioned current checkpoint with Essenty `StateKeeper`, which the Android
host connects to saved instance state. After system-initiated process death, a new
`InstanceKeeper` restores search data, weather data and pending commands before creating children.
Decompose independently restores the screen stack. Completed reads are retained; pending HTTP
reads are issued again. Debounce and timeout clocks resume with the delay remaining at save time,
so time spent without a process is paused. Saving again after restoration is supported.

Only the latest resumable application checkpoint is saved, not the timeline or preview selection.
A transient checkpoint with unacknowledged effects/terminal commands falls back to the latest
resumable frame. Unsupported machine versions or invalid checkpoint payloads start fresh.
This is Android saved-state restoration of the task, not durable storage after force-stop or
removing the task. It restores the last saved checkpoint, not inputs accepted after that save.
The bounded city results and six-hour forecast fit the example's saved state; larger production
payloads should live on disk with only a reference in saved state.

History is memory-only and disappears on full application restart. The count limit is not a
byte quota. Selection and preview can each pin an immutable application frame after eviction.
Android release uses the ordinary root without diagnostics; Android debug, Desktop, Web and iOS
opt into `debugTools` and `actron-timetravel-compose`.

Tests cover bounded history, pinned selection after eviction, action details, preview/continue,
restoring navigation and a pending child request, local timestamp formatting, and lifecycle
retention. Core restart tests verify command queues, remaining timer delays and entry hooks.


Desktop `:weather:desktopApp:run` copies its classpath into a per-run temporary snapshot before
starting. Rebuilding project JARs while the app is open therefore cannot invalidate classes
that the running app has not loaded yet. Normal exit removes the snapshot.

## MVI execution

The root uses `Dispatchers.Main` for machine processing and Decompose navigation. Search and
weather use typed `commandHandler` / `commandHandlers` to call domain services and return actions;
they never write state. Each machine commits decisions sequentially. Both screens collect
with `collectAsUiState`, explicitly separating UI collection from the machine dispatcher.
The shared history adapter is UI-confined together with navigation. See the
[library's threading and command contract](../../doc/guides/machine-mvi.md) before configuring
a background processor in a different host.

## Share a view-only recording

Enable the inspector through Settings. **Import** and **Export** are on the right of its
machine filter, not in Settings. Export suggests `Weather_yyyy-MM-dd_HH-mm-ss.actron.json`
using the local time zone. Configure the project name through
`WeatherHistory(exportSettings = HistoryExportSettings("My project"))`; the library has no
Weather-specific project name. Project name and export instant are also stored in JSON.

Import opens a separate read-only session: selecting a recorded event renders the original
search or weather screen, including pending, failed and successful requests. No stores,
HTTP requests, subscriptions, commands or timers are started by import. Existing live work
may complete independently in the background. Back to session restores the existing live
view; closing or failing an import never replaces its state or history. A file cannot be used
with Continue here. Export while viewing a file re-exports its display recording.

The viewer validates the file format, display schema, both machine versions and structural
graph declarations. Incompatible files are rejected, including changes to a graph without a
version bump. Reducer/handler behavior changes with the same graph still require an explicit
machine version bump. The file is a display recording, not a runnable test or checkpoint.

Validation:

- `:actron-timetravel:jvmTest`: round trips, original event IDs, invalid UTF-8/JSON, bounds,
  metadata, incompatible schemas/versions/graphs and redaction.
- `:weather:debugTools:jvmTest`: screen selection, no continuation controls, live request and
  component isolation, cancelled/failed import preservation and Desktop file I/O.
- `:weather:androidApp:connectedDebugAndroidTest`: actual system document picker save/open and
  cancellation on an Android emulator; no intercepted intents.
- Browser and macOS native file dialogs are additionally exercised manually during development.

## Automatically generated tests and recordings

[GeneratedWeatherScenarios.kt](debugTools/src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt)
shows the developer-facing `machine.scenarios(initial) { ... }` API:

- `actions`: finite search values (empty, Moscow, Kazan), global refresh, forecast/advice retries and advice dismissal.
- `replies`: success/failure data derived from commands actually emitted by the machine.
- `rule`: each query edit immediately cancels the old request; old search answers cannot
  change context. Production composition tests separately check that local child retries preserve siblings.
- `goal`: require a late Moscow reply during Kazan's request, subsequent Kazan success, and
  a concrete successful current-weather path. Production composition tests cover independent response order and retries.

Actron discovers the order of actions, real debounce/timeout timers and command responses from
the graph and decisions. The tests do not prescribe the Moscow→Kazan sequence. They verify
both a stale command result (rejected by command identity) and a detached stale action (rejected
by the search revision guard). The test library's own deliberately broken-guard fixture proves
that the generator detects and reproduces this kind of bug.

Cancellation is the primary behavior: editing Moscow to Kazan exits `Loading`, cancels Moscow's
command and HTTP coroutine immediately, and only then waits 350 ms before starting Kazan.
The generated traces assert the cancelled scope and removal of the old command at that edit.
`CitySearchTest.changingCityCancelsHttpBeforeNextDebounceAndOnlyNewRequestCanFinish` also drives
the actual Store → handler → repository → Ktor MockEngine chain, verifying that the
suspended HTTP operation receives cancellation before Kazan starts. Late-answer injection is a
separate defensive check for an already queued callback or a source that ignores cancellation;
it does not mean the obsolete HTTP request is intentionally kept running.

With the checked-in data and bounds, search explores 763 decisions and selects 7 scenarios,
covering **11/11 transitions**. Current Weather explores 12 decisions / 4 scenarios (**7/7**),
Hourly 27 / 6 (**11/11**), and Advice 21 / 8 (**14/14**). A separate parent test covers its Refresh
transition and single emitted event. The child models cover all declared timer transitions. This is bounded pure
model coverage, not exhaustive payload/guard coverage or verification of executor scheduling.
Existing queued-store tests continue to cover actual command execution and cancellation.

Run the tests and produce ready-to-import recordings:

```sh
./gradlew :weather:debugTools:jvmTest --tests '*Generated*'
```

Files appear in `examples/weather/debugTools/build/generated-scenarios/`:

- `late-moscow-command.actron.json`: search Moscow, switch to Kazan, ignore Moscow's late
  command result, then display Kazan's result.
- `late-moscow-action.actron.json`: the independent revision-guard check.
- `current-weather-success.actron.json`: a standalone current-weather machine reaches Success.

Enable the inspector and use **Import** beside the machine filter. Select events to see the
recorded search/weather screen, before/action/after and graph coverage. The export includes
states and event details, so the ignored Moscow reply is visible even though it changes no data.
These files use Weather's existing schema/version/graph validation and remain view-only.
Importing them runs no test, command or HTTP request. Executable typed scenarios remain in the tests.

## Temporal rules and graph profiling

[AdvancedWeatherTestingTest](debugTools/src/commonTest/kotlin/actron/example/weather/AdvancedWeatherTestingTest.kt)
demonstrates bounded temporal rules, real executor schedule exploration and mutation testing.
See the [developer guide](../../doc/guides/temporal-schedules-and-profiling.md) for API usage.

In the inspector, select an event, open **Graph**, then enable **Profile**. Click a state or
transition to inspect completed queue/processing/command samples. Weather stores immutable
measurements before each event, per executor instance; old history never follows live metrics.
Imported display recordings currently omit profiling. Continuing from history starts a new
measurement window with the recreated executor.


The Android release graph uses only `shared`; debug adds `debugTools`. Desktop/Web and the
included SwiftUI host opt into the debugger explicitly. There is one Weather application;
the module split keeps diagnostics out of normal runtime dependencies. The production tests
verify that recorder/panel classes are absent and checkpoint restoration still works.

UI translations use native XML resources: [localization guide](../../doc/guides/localization.md).
