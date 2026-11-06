# MVI with MachineStore

`MachineStore` is the runtime for MVI screens. The typed DSL defines behavior;
the runtime serializes inputs, commits snapshots, executes commands, and feeds their results
back to the same queue. Reducers use Actron's own StateChart implementation. The low-level Store
engine is internal; applications cannot install competing handwritten reducers.

## One writer, one ordered input stream

```text
UI / external producer ── action ──┐
command handler ── result action ──┼─ queue → Machine.decide(current snapshot, input)
timer ── timer input ──────────────┘                         │
                                                one snapshot commit
                                                   │             │
                                             StateFlow/UI    commands/timers
```

- Inputs are decided sequentially in acceptance order **within one machine**. Concurrent
  callers have no universal wall-clock order; acceptance establishes it. Accepted inputs can
  still be discarded when the store closes. Admission policies can refuse new inputs.
- Only the machine decision commits the next snapshot. Configuration does not expose
  arbitrary state handlers, reducers, or recovery that can replace that snapshot.
- State-local DSL handlers are branches of the same pure decision, not independent writers.
  Keep context immutable (including nested collections); the runtime cannot prevent mutation
  through an application-owned mutable reference. Do not perform I/O in guards/reducers/hooks.
- Commands execute after commit, outside the processing lock. They may run concurrently;
  their result actions are processed sequentially against the then-current snapshot.
- Activation/command identities reject results from commands no longer registered, including
  responses arriving after exit. Lane policies control replacement, queuing and concurrency.
- `resumeMachineStore` creates a new runtime from a checkpoint. It is not a state setter on a
  running store. Close the old store when replacing it; resume requires explicit checkpoint recovery and running
  requests may execute again. Use restartable handlers.

## Processing and UI threads

Choose execution policy when creating the runtime; keep dispatcher objects out of the pure
machine definition, which can be replayed without coroutines:

```kotlin
val store = MachineStore(
    machine = machine,
    context = initialState,
    handler = handler,
    scope = commandScope,
    coroutineContext = Dispatchers.Main,
)
```

`coroutineContext` controls decisions and snapshot commits. `scope` controls command execution,
timers and event delivery, and supplies their parent lifetime. They are separate contexts.
Pass a scope with the desired command dispatcher; suspending network calls need not block
Main, but blocking repositories must switch to a suitable dispatcher themselves.

| Processing dispatcher | Sequential decisions | Same physical thread |
|---|---|---|
| `Dispatchers.Main` | Yes | Platform UI thread |
| Dedicated single-thread dispatcher (JVM) | Yes | That thread; the owner must close it |
| `Dispatchers.Default` / `Dispatchers.IO` (where available) | Yes | No: a pool may select different threads |

The default processing context is `Dispatchers.Default`; choose Main explicitly for UI-thread
processing. Do not use Unconfined for thread confinement. Each machine has its own queue;
two machines sharing a thread do not acquire a shared global history order automatically.

For Compose, use the independent UI adapter from `actron-compose`:

```kotlin
import actron.compose.collectAsUiState
import androidx.compose.runtime.getValue

// Inside a composable, on the platform UI thread:
val snapshot by store.state.collectAsUiState()
```

This collects on `Dispatchers.Main.immediate` and updates Compose state there regardless of
the producer's dispatcher. Collection is cancelled with the composition. It does not move
a wrongly created background composition to Main. The host must supply Main (for example
`kotlinx-coroutines-swing` on Desktop). Plain `Store.observeState` callbacks run in the store
context and must not mutate UI directly from a background processor. For non-Compose UI,
collect `store.state` in a UI-owned Main scope.

`StateFlow` conflates equal/rapid states; rendering is not an audit log. Use decision observers
or the bounded recorder to capture every decision, independently of UI collection.

## Runtime configuration

`MachineStore(...) { ... }` exposes `MachineConfiguration<C, A, E>`. Plugins use the feature's
`A` action type; dispatch from a plugin enters the same admission queue as dispatch from UI.
Scheduler inputs, command completion and timer bookkeeping remain inside the executor.
Plugins observe committed snapshots and machine effects; they cannot install another reducer.

```kotlin
MachineStore(machine, initial, handler, scope) {
    plugin(receiveMessages { message ->
        if (message == RefreshRequested) dispatch(Action.Refresh)
    })
}
```

The example uses `actron.message.receiveMessages`; domain messages/actions are application types.
Snapshot persistence, failure handlers and execution policies remain independent runtime options.

## Request handlers

For one request type, return an action directly. The library handles result emission and
cancellation, including a late response from a cancelled, non-cooperative request:

```kotlin
fun currentWeatherHandler(repository: WeatherRepository): CommandHandler<LoadCurrentWeather, CurrentWeatherAction> =
    commandHandler(
        onFailure = { _, _ -> CurrentWeatherAction.Failed("Weather request failed") },
    ) { request ->
        CurrentWeatherAction.Loaded(repository.load(request.city))
    }
```

For several request types:

```kotlin
val handler = commandHandlers<WeatherCommand, WeatherAction> {
    handle<WeatherCommand.Current>(
        onFailure = { _, error -> WeatherAction.Unavailable(error.message ?: "Weather request failed") },
    ) { request -> WeatherAction.Loaded(current.load(request.city)) }
    handle<WeatherCommand.Hourly>(
        onFailure = { _, error -> WeatherAction.ForecastFailed(error.message ?: "Could not load hourly forecast") },
    ) { request -> WeatherAction.ForecastLoaded(hourly.load(request.city)) }
}
```

Exactly one handler must match a command. Missing/overlapping registrations fail before
executing the request; the scheduler reports this as a command failure. Routing is checked at
execution time, not compile-time exhaustiveness. `onFailure` explicitly maps service exceptions
into domain actions; cancellation is rethrown, never rendered as an error. Unexpected exceptions
not mapped by the application can be rethrown for the runtime exception handler.

For subscriptions with multiple results, retain `CommandHandler { envelope, results -> ... }`.
It also cannot write store state. Timers, lane policies, checkpoint identities and history
work identically with both handler APIs.

See the [actual search machine](../../examples/weather/shared/src/commonMain/kotlin/actron/example/weather/feature/cities/presentation/CitySearchMachine.kt)
and [Weather machine composition](../../examples/weather/shared/src/commonMain/kotlin/actron/example/weather/app/WeatherMachines.kt).
