---
name: actron-test
description: Test Actron machines with virtual time, scripted command results, transition coverage and replay regressions. Use when writing or diagnosing tests of Actron behavior, cancellation or restoration.
---

# Test Actron behavior

Locate the checkout/version and read `actron-statechart-test/README.md`. Verify available
symbols under `actron-statechart-test/src/commonMain/`: `MachineTestDriver` is the underlying
executor; use `machine.test { ... }` only if `MachineTestDsl.kt` exists in this version.
Paths below are repository-relative. For an ordinary Store instead of a Machine, read
`actron-test/README.md` and use that module's awaiting/recording helpers.

## Scenarios

Use `runTest` and its scheduler. The machine driver owns a real queued Store, a virtual clock,
and scripted IO. Advance logical time rather than sleeping; feed command results through the
driver instead of mutating context. Match a command explicitly when parallel requests exist.
Do not accidentally complete the first command when the scenario expects a specific region.

Start from a real initial state and reach states through actions/results/timers. Assert the
state and relevant data after each meaningful step. For parallel machines, check that a result,
retry or timeout in one region preserves the other region's state. For separate machines
connected by group routes, exercise the production composition: verify initial fan-out,
refresh routing and independent failures. `WeatherRoutingTest` covers this boundary;
a group route does not imply a single atomic snapshot across machines.

When the scenario DSL is available, use its automatic cleanup and strict pending-work check.
Otherwise wrap `MachineTestDriver` in `try/finally { driver.close() }`, explicitly start it,
settle steps and assert leftover work. Use the driver's advanced API for streamed results,
effects, failures and restoration. Ending with pending work must be an intentional test case.

Select meaningful edge cases from the model: debounce at the boundary, replacement/cancellation,
stale generations or mismatched identities, error/timeout recovery, hierarchy exit, invoke
restart and restoration. For persistence, round-trip the real codec/checkpoint and verify
what re-executes; restoring a request is not resuming arbitrary suspended instructions.
Behavior DSL unfinished `onEnter` work restarts from the beginning; Weather debounce and
request timeout start fresh. Explicit chart timers instead retain their remaining delay.
Test behavior entry outcomes through the queued executor, not `Machine.decide` alone.

## Coverage and reports

Use the existing `driver.coverage`, `merge`, `assertTransitionsCovered` and `describe` APIs.
Merge only reports from the same definition/version/declaration universe. Exercise reachable
source states rather than manufacturing a green report by inserting transition IDs.
Distinguish transition coverage, state coverage, guard outcomes and input-data coverage.
100% transitions is not exhaustive correctness. Bounded exploration can be incomplete;
report exhausted budgets and missing obligations explicitly.

Weather examples live in `examples/weather/debugTools/src/commonTest/`; if present, read
`CitySearchScenarioTest`, `WeatherScenarioTest` and `WeatherMachinesCoverageTest` for the
current DSL. Do not assume those files exist on an older release.

Typical focused checks from the repository root:

```sh
./gradlew :weather:debugTools:jvmTest
./gradlew :actron-statechart-test:jvmTest
./gradlew :actron-statechart:jvmTest
```

Choose the affected module rather than running all targets blindly. Test UI changes in
`:actron-timetravel-compose:jvmTest`; use actual platform compilation/tests for platform-specific
claims. Inspect XML test reports under `<module>/build/test-results/` on failure. Run formatter
and style checks sequentially. State exactly which commands passed, which failed, and the
measured coverage. Never relabel CI infrastructure failures as passing tests.
