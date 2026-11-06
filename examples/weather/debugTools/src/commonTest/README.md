# Testing the Weather machines

[Example README](../../../README.md) · [Architecture](../../../docs/architecture.md) ·
[Actron test API](../../../../../actron-statechart-test/README.md)

All five machines use `screenMachine` with declared transitions and activation-owned entry work.
Tests run the queued Store with injected repositories and virtual time. HTTP decoding
tests use Ktor MockEngine, never the public weather server.

| File | Contract checked |
| --- | --- |
| AdvancedWeatherTestingTest | Finite executor schedules, pending requests, cancellation, restart, city guard |
| CitySearchGraphTest | Exact declared edges, response guard outcomes and remaining deadlines on resume |
| CitySearchScenarioTest | Debounce, success, failure and clearing |
| WeatherScenarioTest | Standalone forecast and advice lifecycles, retry and dismissal |
| GeneratedWeatherScenarios | Recorded executor scenarios with late entry result and late action |
| GeneratedWeatherScenariosTest | Replay without I/O and importable display recordings |
| CitySearchTest | Store/entry/repository integration and cancellation of HTTP |
| WeatherMachinesCoverageTest | Observed state-pair coverage and replacing a query from every phase |
| WeatherHierarchyTest | One parent refresh, three independent children, independent dismissal |
| GeneratedRecordingFilesTest | JVM export of executed scenarios |

## Drive an actual machine

```kotlin
CitySearchMachine.create { emptyList() }.test(scope = this, initial = CitySearchState.Idle()) {
    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
    expectState<CitySearchState.Debouncing>()
    advanceTimeBy(duration = 350.milliseconds)
    expectState<CitySearchState.Success>()
}
```

`dispatch` settles immediate queued work. `advanceTimeBy` advances coroutine virtual time.
Inject a repository to control the result or await cancellation. The driver
asserts no unexpected failures or pending work and closes its executor. `allowPendingWork` is
only for scenarios intentionally ending during execution.

## Explore delivery schedules

`exploreSchedules` runs a fresh real executor for each bounded prefix. Tests provide finite
choices such as answering the current request, editing the query, advancing time or restarting.
Assertions check the state after each step, and failures retain the actual operation sequence.
This covers entry coroutines and cancellation; a pure static graph explorer cannot execute them.

Weather tests exercise declared edges, accepted and rejected guards, and cancellation on exit.

## Cancellation and late outcomes

Changing query exits the old activation before the new debounce finishes. Repository integration
checks cancellation reaches HTTP. A recorded scenario also feeds an obsolete entry result
through the production decision entrypoint and verifies rejection, then sends an obsolete action
to check revision protection independently. The core DSL tests additionally use a NonCancellable
old entry coroutine to reproduce delivery after exit.

## Record and replay

`GeneratedWeatherScenarios.search(scope = this)` records a concrete executed scenario. Its trace
can be replayed through `Machine.decide` without launching `onEnter`. The display exporter retains
ignored outcomes, context, active nodes and observed transition hits. JVM tests write importable
files under `debugTools/build/generated-scenarios/`.

```sh
./gradlew :weather:shared:jvmTest :weather:debugTools:jvmTest
```
