# actron-statechart-test

Use this to test **what a Machine decides** when commands complete or virtual time advances.
The test supplies command answers instead of calling a real repository. Start with the
[pure Machine example](../README.md#3-a-machine-makes-decisions-io-runs-afterward); for an
ordinary Store, [actron-test](../actron-test/README.md) is sufficient. Dependency availability
and composite-build setup follow [installation](../README.md#installation).

The driver owns a Store on your `TestScope` scheduler. `send` supplies an action, `answer`
supplies a command result, and `advanceBy` moves virtual time. Assertions check context,
active nodes and leftover work rather than waiting for real wall-clock delays.

Test support for [actron-statechart](../actron-statechart/README.md)'s `MachineStore`, on top of
[actron-test](../actron-test): the TestStore-like API of the upstream roadmap (koma-kt/koma#189, item 2)
with the machine's meaning.

- **`MachineTestDriver(machine, context, scope)`**: the store on the test's scheduler with a
  `VirtualMachineClock`, its commands waiting for the test through a `ScriptedCommandHandler`, its
  snapshots and effects recorded. `start()`, `send(action)`, `settle()`, `advanceBy(duration)`,
  `advanceUntilIdle()`, `answer(command, result)`, `complete(command)`, `fail(command)`,
  `receiveEvent<E>()`, `pendingEffects` / `acknowledge(id)`, `assertActive(nodes)`,
  `assertContext(expected)`, `assertNoPendingWork()`, `close()`. Every step returns the executor's
  `ExecutorCheckpoint` once the store settled.
- **`MachineStore.settle(timeout)`**: `awaitIdle` then `checkpoint()`; **`ExecutorCheckpoint.pendingWork()`**:
  the commands running, queued and ending, the timers with what is left, the effects pending;
  **`MachineStore.assertNoPendingWork()` / `assertNoPendingWork(recorder)`**: fails naming all of it and the events not received.
- **`VirtualMachineClock(scheduler)`**, `TestScope.machineClock()`: a `MachineClock` on virtual time.
- **`ScriptedCommandHandler`**: runs nothing by itself; records what the executor started and
  cancelled; the test answers, completes or fails each command, as a replay's `Branch` does.

Status: **supported API**, in the fork [roman-n1/actron](https://github.com/roman-n1/actron).

## Dependency

```kotlin
// test source set only
implementation("io.github.roman-n1:actron-statechart-test:5.0.0-alpha.1")
```

## Quick start

```kotlin
@Test
fun aLoad_fetches_andShowsTheContent() = runTest {
    val driver = MachineTestDriver(machine, Ctx(), this)
    driver.start()

    driver.send(Act.Load)                                   // decided: Loading, the Fetch command registered
    val fetch = driver.runningCommands.single()
    driver.assertActive(setOf(root, loading))
    assertEquals(Ev.Started, driver.receiveEvent<Ev.Started>())

    driver.answer(fetch.id, Act.Loaded)                     // the result decided: Content
    driver.complete(fetch.id)
    driver.assertActive(setOf(root, content))
    assertEquals(Ev.Done, driver.receiveEvent<Ev.Done>())

    driver.assertNoPendingWork()                            // no command, no timer, no effect, nothing unreceived
    driver.close()
}
```

## Model-based testing

For automatic scenario discovery, start with [data, rules and goals](#generate-scenarios-from-data-rules-and-goals).
The lower-level generator below remains available for custom exploration strategies.

Declare pure predicates once on the machine; tests, replay and debug tools share them:

```kotlin
val machine = Machine<Context, Act, Command, Ev>(id, version, chart) {
    invariant("signed-out-has-no-chat") { snapshot ->
        !snapshot.isActive(signedOut) || !snapshot.isActive(chatOpen)
    }
    // Optional in debug runtime: a violation rolls back the whole macrostep before IO.
    // enforceInvariants()
}

val inputs = MachineInputGenerator<Context, Act> { snapshot, now ->
    buildList {
        add(MachineInput.Dispatch(Act.Logout, now))
        if (snapshot.isActive(signedIn)) add(MachineInput.Dispatch(Act.OpenChat, now))
        // Supply valid typed payloads / command results here; no reflection or real IO.
    }
}.withTimers()

val initial = machine.initialSnapshot(Context())
val report = machine.explore(initial, inputs, maxDepth = 20, maxDecisions = 10_000)
println(report.coverage.states.percent)
println(report.coverage.transitions.missing)
println(report.coverage.guards.percent) // true/false outcomes per transition

report.failures.firstOrNull()?.let { failure ->
    val minimal = machine.shrink(initial, failure.inputs)
    println(minimal.failure.inputs) // actual inputs, payloads, virtual times
    check(!minimal.truncated)       // otherwise minimality has not been established
}
report.assertSuccess()             // also rejects an exhausted decision budget
```

`ExplorationStrategy.RandomWalk(seed = 42, runs = 1_000)` selects reproducible random input
choices. Breadth-first search keeps different contexts/history even when active nodes are equal.
`report.scenarios` contains executable prefixes that cover the transitions actually reached;
`machine.replaySequence(initial, scenario.inputs) { failure -> ... }` reruns one and reports
its first failure through the callback, returning whether it failed.
Neither coverage nor a successful bounded run proves safety beyond supplied payloads and depth.
`MachineTestDriver` also checks every recorded stable snapshot on settle, including transient
commits between two calls. Runtime-enforced violations appear in the driver's `failures` list.

## Actual test coverage and generated plans

`driver.coverage` accumulates actual transitions, successful macrostep entries, timer transitions
and guard true/false outcomes without evaluating guards twice. Merge reports from tests of the
same definition/version with `coverage.merge(other)`, print `coverage.describe(machine.chart)`
and assert all or selected transitions with `coverage.assertTransitionsCovered(required)`.
`MachineCoverageRecorder(machine)` is the equivalent observer for a directly constructed Store.

```kotlin
val plan = machine.generateTestPlan(initial, inputs, maxDepth = 10, maxDecisions = 10_000)
plan.assertReady(requireOptimal = true)
val result = machine.runPlan(initial, plan)
result.assertSuccess()
```

The planner minimizes scenario count within the discovered executable prefixes. Selection has
its own budget and reports `selectionOptimal`; exhausted discovery, unreachable declarations,
missing payloads and invariant failures remain explicit. `ExplorationStrategy.AllTransitions`
stops as soon as every declared transition has actually fired; breadth-first search can discover
longer scenarios combining branches. `driver.generateTestPlan(inputs)` and `driver.verifyPlan(plan)`
are independent pure-model operations, leaving its live Store and scripted IO untouched.

`machine.runScenario(initial, MachineScenario(name, inputs))` also runs saved regression prefixes.
Shrinking accepts an optional `preservesFailure` matcher for distinct bugs of the same exception
type. Match domain context/inputs/identities; exception messages are omitted by default.
See the [contracts and examples](../doc/internal/design/2026-10-06-derived-behavioural-features.md).

## Coverage targets, payloads and CI artifacts

Use `machine.inputGenerator(listOf(actionGenerator<Context, Act>("action") { snapshot, now ->
payloads(snapshot, now) }))` to provide a finite payload domain. Blocked actions are retained
by default so false guard outcomes can be observed. Timers use their actual ids/deadlines;
`constraint` filters inputs and `extraInputs` supplies application-owned command responses.

The targeted `generateTestPlan(initial, inputs, target)` overload supports `CoverageTarget`
for all states, transitions, guard outcomes, timer/history paths, explicit error states and
custom target states. Combine obligations with `chart.requirements(target)` and
`CoverageTarget.Custom`. Execute with `runPlan(initial, plan).assertSuccess()`; missing
coverage and incomplete discovery remain explicit. `exploreDetailed` exposes actual prefix
state/guard observations without a second decision. Driver `explore` and targeted plan helpers
use an independent pure model and leave its live Store untouched.

`machine.behaviouralSnapshot(coverage, sources)` exports a versioned `.actron.json` model,
with optional active/selected transition data for the IDE. `behaviouralReviewArtifacts(before,
after)` produces diff, coverage, Mermaid and transition-matrix files. Keep the baseline in Git
and compare with the PR base revision. See the executable
[review exporter](../verification/behaviour-review/src/jvmMain/kotlin/actron/review/Review.kt),
[IDE plugin](../tooling/intellij-plugin/README.md) and
[five-wave contracts](../doc/internal/design/2026-10-06-competitive-roadmap.md).

## Scenario DSL

`machine.test(scope, initial) { ... }` starts a real queued `MachineStore`, uses scripted
commands and virtual time, checks unexpected runtime failures and pending work, then closes
its execution scope in `finally`, including after assertion failures. It returns the same
`MachineCoverage` report as the driver. No real HTTP or database operation runs.

```kotlin
val coverage = CitySearchMachine.create().test(this, CitySearchState.Idle()) {
    expectState<CitySearchState.Idle>()
    dispatch(CitySearchAction.ChangeQuery("London"))
    expectState<CitySearchState.Debouncing>()
    advanceTimeBy(350.milliseconds)
    expectState<CitySearchState.Loading>()
    completeCommand { command -> CitySearchAction.Found(command.revision, emptyList()) }
    expectState<CitySearchState.Success>()
}
```

Inside `runTest`, pass `this` as the scope. Direct-state machines can use
`expectState<Ready>()` without a selector; parallel machines select each region's state.
`completeCommand(matching = { it is Forecast }) { ... }` requires exactly one matching
running command before sending its result and completing it (unless its state already exited).
Zero or multiple matches fail with a diagnostic. Use `driver` for multi-result streams,
effects, failure injection and other advanced controls. `allowPendingWork = true` explicitly
allows ending a scenario with work still running; the scope is still closed afterwards.

See executable Weather examples: `CitySearchScenarioTest` (debounce/success/error/clear/timeout)
and `WeatherScenarioTest` (two parallel requests, selective completion and independent timeout).

## Generate scenarios from data, rules and goals

The graph supplies declared actions, transitions and timers. The developer supplies a finite
domain of typed payloads and command answers, plus the properties the application must preserve.
Actron explores executable prefixes through the real pure machine decision function, starting
from its initial context. No ordered test script or fabricated command IDs are needed.

```kotlin
val model = CitySearchMachine.create().scenarios(CitySearchState.Idle()) {
    actions { snapshot ->
        // Limit the data domain and number of edits, not their order.
        if (snapshot.context.revision < 2) {
            listOf("", "Москва", "Казань").map(CitySearchAction::ChangeQuery)
        } else {
            emptyList()
        }
    }
    replies(ReplyMode.entries.toSet()) { command ->
        // An actual SearchCitiesCommand emitted on this path, with its real revision.
        listOf(
            CitySearchAction.Found(command.revision, fixtures.getValue(command.query)),
            CitySearchAction.Failed(command.revision, "offline"),
        )
    }
    rule("results-belong-to-query") { step ->
        val state = step.after.context
        state !is CitySearchState.Success || state.results.all { it.city.name == state.query }
    }
}
val generated = model.generate(maxDepth = 6, maxDecisions = 100_000)
generated.assertSuccess()
for (scenario in generated.scenarios) {
    val trace = model.record(scenario)
    trace.assertSuccess()
    println(trace.coverage.describe(model.machine.chart))
}
```

`actions` are filtered to the action types declared at active nodes; guards are evaluated only
by the decision function. Timers are automatically supplied with their registered identity and
deadline. `replies` receives the original typed command payload, never a guessed response revision.
Its modes distinguish:

- `Active` (default): a result for a currently registered command.
- `LateCommand`: a result with the original command ID after that command is no longer registered.
  This checks stale-command rejection by the machine.
- `LateAction`: the same old payload delivered as an action after its command is gone.
  This independently checks application guards when callbacks bypass command-result delivery.

Late modes inject a result after a command is gone; they do not keep a cancelled request alive.
Cancellation must also be asserted: Weather checks that a query edit removes the old command
and emits its cancelled scope immediately, then separately verifies propagation through its
real command handler/repository into Ktor MockEngine before the next debounce expires.

Each distinct reply per command, and each distinct detached reply, is offered once per path.
The generator does not infer command completion, stream repetition, invoke routing or executor
lane order. Supply those inputs explicitly through `inputs { snapshot, now, steps -> ... }`,
or use `MachineTestDriver` to test the real queued executor. Data generators and predicates must
be deterministic, finite and free of IO; contexts and actions must be immutable.

**Rules check every explored decision; goals require a concrete witness.** A rule that rejects
stale results can pass without any stale result arriving. Add a goal to require that situation:

```kotlin
goal("late-moscow-command") { steps ->
    val step = steps.last()
    val input = step.input
    if (input !is MachineInput.CommandResult) {
        false
    } else {
        val action = input.action
        action is CitySearchAction.Found &&
            action.results.any { it.city.name == "Москва" } &&
            step.before.context.query == "Казань" &&
            step.before.context is CitySearchState.Loading &&
            input.command !in step.before.commands
    }
}
```

The full Weather model also requires Kazan to succeed after that stale reply, checks that old
revisions leave context unchanged, and explores both response orders of the parallel weather
machine. See [GeneratedWeatherScenarios.kt](../examples/weather/src/commonTest/kotlin/actron/example/weather/GeneratedWeatherScenarios.kt).

The default target is every declared transition. Change `target` to require states, guard
outcomes or custom obligations. Generation stops once the requested coverage and all goals
are reached, or a bound/failure stops exploration. `assertSuccess()` rejects failures,
exhausted decision budgets, missing coverage and missing goals. Reaching the target is **not**
exhaustive verification of every sequence, arbitrary payloads or deeper histories. The selected
scenario set covers the reached obligations and includes goal witnesses; minimality is not promised.

Inspect `generated.exploration.failures` for exact failing typed inputs and snapshots.
`model.record(MachineScenario("regression", failure.inputs))` reproduces a counterexample,
including external rules. `record` retains every actual before/input/after decision, even ignored
actions and stale replies; recording stops at the first failure. Replay requires the same machine,
initial context and deterministic callbacks. Keep machine definitions/version changes explicit.

## View generated scenarios in Time Travel

Add the optional [`actron-timetravel-test`](../actron-timetravel-test/README.md) dependency in
your test source set and import `actron.timetravel.test.recording`. The generator module
itself has no dependency on Time Travel.

`trace.recording(...)` projects a typed in-memory trace into the existing `HistoryRecording<S>`
format. Supply the application's display schema, current graph definitions, context serializer
and a projection to its display frame:

```kotlin
val recording = trace.recording(
    schema = codec.schema,
    definitions = definitions,
    describe = { Json.encodeToJsonElement(Context.serializer(), it) },
    snapshot = { step, coverage -> displayFrame(step.after, coverage) },
    settings = HistoryExportSettings("My project"),
)
val bytes = codec.encode(recording)
```

The recording contains event labels, before/after fields, actual transition coverage and display
snapshots. Virtual input times become offsets from `startedAt`. Use the `details` transform and
the display projection to redact sensitive values before encoding. File saving remains a host
responsibility; the test module has no Android, Decompose or platform file-picker dependency.

Import the file into the compatible application inspector to scrub the generated sequence and
see its screens and highlighted graph. This is a **view-only recording**: it cannot continue
execution, start HTTP or restore commands. The typed `MachineScenario` is the executable test
artifact in memory/source; the exported JSON is its display artifact. Weather includes an
[executable exporter](../examples/weather/src/jvmTest/kotlin/actron/example/weather/GeneratedRecordingFilesTest.kt).

## Temporal rules, executor schedules and mutation testing

Use `TemporalRules` for bounded response obligations and prefix safety rules,
`exploreSchedules` for actual executor response orders/cancellation/restart, and
`testMutations` to measure whether your assertions detect a changed graph declaration.
These are complementary to pure graph scenario generation. Unfinished obligations,
truncated exploration, surviving mutants and harness errors remain explicit.

The [guide with Weather examples](../doc/guides/temporal-schedules-and-profiling.md)
explains inputs, assertions, result interpretation and scope. `MachineTestDriver` also
accepts a settled `checkpoint` for a restored executor. Each driver begins its own
virtual time at zero; a restored driver preserves the checkpoint's logical time.
