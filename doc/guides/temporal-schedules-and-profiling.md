# Temporal rules, executor schedules, mutation tests and profiling

These tools answer four different questions:

| Question | API | Runs production IO? |
| --- | --- | --- |
| Did every request finish or get cancelled within a bound? | `TemporalRules` | No |
| Does the executor work in both response orders, including restart? | `exploreSchedules` | No; real executor, scripted handlers |
| Would my tests detect a broken transition, guard or reducer binding? | `testMutations` | Only what your test callback runs |
| Where does the running application spend time? | `MachineProfiler` | Observes the application's existing handler |

Test APIs live in `actron-statechart-test`. Profiling lives in `actron-statechart`;
its optional graph UI lives in `actron-timetravel-compose`. None depends on Android
or Decompose.

## 1. State a temporal obligation

An invariant checks a snapshot. A temporal obligation relates a trigger to a later
observation. Use typed predicates to say what starts and satisfies the obligation:

```kotlin
val rules = TemporalRules<
    CitySearchState, CitySearchAction, SearchCities, NoEvent
>().apply {
    respondsWithin(
        name = "each-search-finishes-or-is-cancelled",
        within = 8.seconds,
        maxSteps = 20,
        triggered = { it.explained.decision.commands.isNotEmpty() },
        satisfied = { started, later ->
            later.after.context.revision != started.after.context.revision ||
                later.after.context !is CitySearchState.Loading
        },
    )
}
val trace = model.record(scenario)
rules.check(trace).assertSatisfied()
```

Here changing the query cancels the old generation and satisfies its obligation.
A new request creates a separate obligation. Without generation correlation, an
unrelated request's answer could accidentally satisfy the rule.

`always(name) { prefix -> ... }` checks each prefix for a safety property. Add
`temporal(rules)` to a `scenarios` builder to reject violations during generation
and recording. At the end of a chosen trace, call `rules.check(trace).assertSatisfied()`
to require that its outstanding obligations were resolved.

The report distinguishes:

- **violations**: a rejected safety property or an exceeded response deadline;
- **pending**: the finite trace ended before the obligation was resolved;
- **triggers**: how often each rule actually applied.

By default, `assertSatisfied()` also rejects a rule with no trigger. Set
`requireTriggers = false` only when vacuous success is intentional. Deadlines use
logical input time, including virtual timer inputs. An answer exactly at the time
deadline is allowed; multiple events may share that timestamp. The independent
step bound prevents an unlimited sequence at the same logical time.

No later observation means no evidence that time advanced: an unfinished trace
is pending, not proof of a timeout. Prefix exploration permits pending obligations;
it does not claim infinite-future liveness or automatically finish every prefix.

## 2. Explore delivery order on the real executor

The pure graph generator tests decisions. `exploreSchedules` additionally exercises
the real queued `MachineStore`, command handler cancellation and virtual timers:

```kotlin
val report = CityWeatherMachine.create().exploreSchedules(
    initial = WeatherContext(kazan),
    scope = this, // inside runTest
    maxDepth = 2,
    maxRuns = 30,
    setup = { send(WeatherAction.Refresh) },
    choices = {
        if (runningCommands.isEmpty()) {
            emptyList()
        } else {
            runningCommands.map { command ->
                val reply = when (command.command) {
                    is WeatherCommand.Current -> WeatherAction.Loaded(reading(kazan))
                    is WeatherCommand.Hourly ->
                        WeatherAction.ForecastLoaded(forecast(kazan))
                }
                ScheduleOperation.Answer(command.id, reply)
            } + ScheduleOperation.Advance(8.seconds)
        }
    },
    check = {
        val current = snapshot.context.current
        if (current is RequestState.Success) {
            assertEquals(kazan, current.value.city)
        }
    },
)
report.assertSuccess()
```

The developer supplies possible next operations and assertions, not their order.
The explorer runs a fresh executor for each prefix and tries every supplied choice
within the bounds. `completed` contains terminal operation sequences; `failures`
contains the first failing path and its assertion message. `assertSuccess()` rejects
failure and truncated exploration. A terminal path must have no pending commands,
timers, effects or unconsumed events.

Operations are `Send`, `Answer`, `Complete`, `Fail`, `Advance` and `Restart`.
`Answer` supplies a result; it does not automatically complete a streaming command.
Use `Complete` where the machine keeps that command's state active.

`Restart` takes a settled checkpoint, closes the old executor and starts a new
one from it. Logical time and remaining timers are preserved; restartable commands
run again. Effects and undelivered terminal outcomes must be consumed first.
Use the path passed to `choices` to limit restarts:

```kotlin
choices = { path ->
    buildList {
        // Add finite answers / advances appropriate to this snapshot.
        if (ScheduleOperation.Restart !in path && runningCommands.isNotEmpty()) {
            add(ScheduleOperation.Restart)
        }
    }
}
```

Setup and choice factories must be deterministic. Restart uses the driver's
scripted handler and the standard restart recovery policy. This explores delivery
orders at settled boundaries, not every CPU thread interleaving, network stack or
application-specific recovery policy.

## 3. Check whether tests notice a broken graph

```kotlin
val report = machine.testMutations(
    listOf(TransitionMutation(loadedTransition, MutationKind.RemoveGuard)),
) { variant ->
    // Run the same test against the supplied variant, not the captured original.
    val driver = MachineTestDriver(variant, WeatherContext(kazan), this)
    try {
        driver.start()
        driver.send(WeatherAction.Refresh)
        driver.send(WeatherAction.Loaded(reading(moscow)))
        assertIs<RequestState.Loading>(driver.snapshot.context.current)
    } finally {
        driver.close()
    }
}
report.assertAllKilled()
```

The original machine must pass first. Each mutant changes one declaration:
`RemoveTransition`, `RemoveGuard` or `RemoveEffect`. `chart.mutationCandidates()`
enumerates applicable mutations; the default tests all candidates.

Only an assertion failure counts as **Killed**. **Survived** means the suite
accepted the change; inspect whether the test is weak or the mutant is equivalent
for the tested domain. **Invalid** means the changed declaration could not be
constructed. **Error** means the harness threw a non-assertion exception. Neither
invalid graphs nor infrastructure errors inflate a success score; cancellation
propagates. `assertAllKilled()` rejects every non-killed outcome.

The tool does not rewrite arbitrary Kotlin bodies. Transition IDs belong to the
original graph when selecting a mutation; removing a transition can shift IDs in
the variant. Assert business behavior, not the mutant's transition numbers.

## 4. Profile the running machine

```kotlin
val profiler = MachineProfiler(machine, capacity = 500)
val store = MachineStore(
    machine = machine,
    context = context,
    handler = profiler.commands(handler),
    scope = scope,
    observers = listOf(profiler),
) {
    profileWith(profiler)
}
```

For an existing checkpoint, create a **new** profiler, call
`profiler.beginAt(checkpoint.snapshot)` before starting, and attach the same observer,
wrapped handler and `profileWith` block to `resumeMachineStore`. Restoration begins
a new measurement window; it cannot recover elapsed wall time from a checkpoint.
Both factories accept admission, mailbox and Store configuration.

`profiler.snapshot()` returns immutable aggregate data:

- transition hits, input queue delay and input processing time;
- state entries and completed residence intervals;
- command delay from decision notification to handler start, handler lifetime and observed cancellation count.

Durations include count, total, mean and maximum. Use a separate profiler for each
executor instance. Correlation storage is bounded; dropped correlations are counted
and do not become fabricated zero-duration samples. Aggregates retain no actions
or command payloads.

Processing includes the reducer, hooks and observers. When one input takes multiple
transitions, its whole processing duration is attributed to each; do not add these
durations together. Handler lifetime includes suspension and IO, not remote CPU.
Parallel state intervals overlap. Completed samples omit currently running work;
cancellation counts apply to handlers that actually began execution.

### Weather inspector

Weather records application snapshots and transition coverage. It does not create profilers,
wrap command handlers for timing, or retain execution-time measurements in history frames.
The inspector shows coverage and zoom controls in one header, with coverage text limited to
three lines. Graph transitions and detail cards do not display timing heat or measurements.
The library profiling APIs described above are independent of the Weather application.

See the executable examples in
[`AdvancedWeatherTestingTest.kt`](../../examples/weather/debugTools/src/commonTest/kotlin/actron/example/weather/AdvancedWeatherTestingTest.kt).
They cover temporal cancellation, both forecast/current answer orders, errors and
timeout, changing Moscow to Kazan, restart during debounce and HTTP, and a removed
city guard. Test handlers return fixtures; these exploration tests make no real HTTP
requests.
