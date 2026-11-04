# Testing the Weather machines

[Example README](../../../README.md) · [Architecture](../../../docs/architecture.md) ·
[Actron test API](../../../../../actron-statechart-test/README.md)

Weather uses `behaviorStateMachine`: synchronous action handlers and suspend state-owned entry
work. Tests run the real queued executor with scripted requests and virtual time. HTTP decoding
tests use Ktor MockEngine, never the public weather server.

| File | Contract checked |
| --- | --- |
| AdvancedWeatherTestingTest | Finite executor schedules, timeout boundary, cancellation, restart, city guard |
| CitySearchScenarioTest | Debounce, success, failure, timeout and clearing |
| WeatherScenarioTest | Standalone forecast and advice lifecycles, retry, dismissal and timeout |
| GeneratedWeatherScenarios | Recorded executor scenarios with late worker output and late action |
| GeneratedWeatherScenariosTest | Replay without I/O and importable display recordings |
| CitySearchTest | Store/handler/repository integration and cancellation of HTTP |
| WeatherMachinesCoverageTest | Observed state-pair coverage and replacing a query from every phase |
| WeatherHierarchyTest | One parent refresh, three independent children, independent dismissal |
| GeneratedRecordingFilesTest | JVM export of executed scenarios |

## Drive an actual machine

```kotlin
CitySearchMachine.create().test(scope = this, initial = CitySearchState()) {
    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
    expectState<RequestState.Debouncing>(select = { it.phase })
    advanceTimeBy(duration = 350.milliseconds)
    expectState<RequestState.Loading>(select = { it.phase })
    completeCommand { command ->
        CitySearchAction.Found(revision = command.revision, results = emptyList())
    }
    expectState<RequestState.Success<List<CityOption>>>(select = { it.phase })
}
```

`dispatch` settles immediate queued work. `advanceTimeBy` advances coroutine virtual time.
`completeCommand` supplies the actual request's result; it does not invoke HTTP. The driver
asserts no unexpected failures or pending work and closes its executor. `allowPendingWork` is
only for scenarios intentionally ending during execution.

## Explore delivery schedules

`exploreSchedules` runs a fresh real executor for each bounded prefix. Tests provide finite
choices such as answering the current request, editing the query, advancing time or restarting.
Assertions check the state after each step, and failures retain the actual operation sequence.
This covers entry coroutines and cancellation; a pure static graph explorer cannot execute them.

Arbitrary Kotlin conditions do not declare a complete reachable graph. The machine chart's
source/target envelope is conservative; do not assert every envelope edge is reachable or mutate
a chart guard to pretend that an ordinary Kotlin `if` was removed. Weather asserts observed
state-pair coverage, positive/negative outcomes, and every relevant business phase. Full static
coverage and guard mutations remain supported for the explicit graph DSL elsewhere in Actron.

## Cancellation and late outcomes

Changing query exits the old activation before the new debounce finishes. Repository integration
checks cancellation reaches HTTP. A recorded scenario also feeds an obsolete activation's outcome
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
