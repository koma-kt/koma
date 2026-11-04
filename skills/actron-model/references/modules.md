# Module routing

Use the root README's Modules table and each `<module>/README.md` for orientation; verify signatures against source/API dumps.
Read only the modules relevant to the task. This map explains boundaries, not every symbol.

| Need | Module / boundary |
|---|---|
| Internal execution engine and shared action/event/lifecycle contracts | `actron-core`; application construction uses `MachineStore` |
| Retained machine setup and lifecycle observation | `actron-decompose` |
| Compose collection/rendering of Store snapshots | `actron-compose` |
| Transient process-wide messages from command handlers | `actron-message`; not a substitute for typed machine commands |
| Logging a Store | `actron-logging` |
| Runtime test helpers for Stores and machines | `actron-test`; machine scenarios belong in the module below |
| Application reducer: our StateChart Machine, typed DSL, hierarchy, parallel states, invoke, executor | `actron-statechart` |
| Deliver retained machine effects in Compose | `actron-statechart-compose` |
| Scripted machine commands, virtual clocks, coverage, exploration | `actron-statechart-test`; test dependencies only |
| Checkpoint codecs/restoration without debug tooling | `actron-persistence` |
| Bounded recording, replay and branching | `actron-timetravel` |
| History panel, graph, resizing, locale, diagnostics UI | `actron-timetravel-compose`; supplies UI, not app navigation |
| Bounded ordered diagnostic journal / storage | `actron-observability` |
| Causal traces spanning decisions, commands, timers and effects | `actron-diagnostics` |
| JVM/Android OpenTelemetry or Sentry integration | `actron-diagnostics-sdk`; optional backend adapter |
| Android Crashlytics integration | `actron-diagnostics-crashlytics`; optional Android adapter |

Examples are applications, not published dependencies:

- `examples/weather`: two-screen Compose/Decompose example, search plus a screen routing to independent current-weather,
  hourly-forecast and advice machines. Production code lives in `shared`, recording/inspector
  integration and scenario tests in `debugTools`, with platform hosts alongside.

Repository-only tooling:

- `verification/behaviour-review`: model snapshots and review artifacts.
- `verification/resource-soak`: resource-budget tests.
- `verification/nullability-guard`: strict nullability checks.
- `tooling/intellij-plugin`: IDE support; not part of the runtime.
- `build-logic`: Gradle conventions; `settings.gradle.kts` gives Gradle project names.

Use `rg --files <module>/src` to find concrete declarations and tests. Do not add a production
dependency on a test module merely to obtain a report type or convenience helper.
