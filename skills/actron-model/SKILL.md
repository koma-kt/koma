---
name: actron-model
description: Design and integrate Actron state machines using its typed Kotlin DSL, choose the relevant modules, and connect execution and Compose UI. Use for Actron-based application or library changes.
---

# Build with Actron

Find the Actron checkout or the consumer's resolved Actron version first. Paths below are
relative to that checkout, not to the installed skill directory. If source is unavailable,
use documentation matching the installed artifact version; do not present branch-only APIs
as released features.

For module selection, read [references/modules.md](references/modules.md). For API shape,
start with `README.md`, `doc/guides/machine-mvi.md` and the relevant module README, then inspect
the signatures in `actron-statechart/src/commonMain/kotlin/actron/statechart/machine/`.

## Modeling decisions

- Our concrete StateChart `Machine` is the only application reducer. Do not introduce a
  handwritten reducer interface, standalone Store DSL, or legacy coroutine chart adapter.
  `StoreBuilder` and `createRuntimeStore` are internal engine infrastructure.

- For MVI screens use `screenMachine<State, Action, Event>`. Each `on<Action>` declares a
  `target { ... }` returning the complete next state, optionally guarded by `guard { ... }`.
  Use `initialType` when initial data is supplied at runtime. Weather uses this DSL throughout.
  Read/write bindings belong to parallel regions.
- In a screen's suspend `onEnter`, call the injected repository and dispatch its result.
  Cancellation propagates on exit or close. The Store validates results against the activation.
  A restored Loading entry starts the read again. Declare deadlines with `after` so the
  remaining duration survives restoration. Use `event` for one-time outputs.
- Use `typedStateMachine` for explicit static graph declarations, hierarchy/history and chart
  timers. Its `internal<Action>` updates avoid exit/re-entry. Behavior graphs conservatively
  include possible targets; arbitrary Kotlin control flow is not exact static reachability.
- Use parallel regions for concurrent phases within one machine/snapshot. For independently
  owned features, use separate machines and `MachineGroup` typed member routes. A route maps
  synchronously and carries zero or one action per event; separate routes provide fan-out.
  Group members retain separate queues and commits. Weather's `app/WeatherMachines.kt`
  demonstrates screen/current/hourly/advice composition, not a single parallel machine.
  Use hierarchical states for shared lifetime and typed invocation for parent-owned children
  requiring an atomic snapshot. Do not rebuild these contracts with flags or detached jobs.
- Keep action rules and guards pure. For machines that explicitly model commands, run work in
  command handlers. Results return as typed actions through the Store. Choose command scopes,
  lanes and cancellation policies to match the domain; do not bypass them with detached jobs.
  Prefer `commandHandler(onFailure = ...)` for one result or `commandHandlers { handle<T> ... }`
  for several command types. Map service errors to domain actions; cancellation propagates.
  Use the lower-level `CommandHandler` callback for streams. Registered handler matching is
  checked at execution time, not compile-time exhaustiveness.
- A Machine describes behavior; MachineStore owns queued processing. Use the documented
  processing/UI dispatch configuration. A dispatcher pool does not imply a dedicated OS thread.
  Do not mutate published state directly or claim all side effects run on the UI thread.
- For Decompose, use `retainedMachine` from `actron-decompose`: the keeper owns the Store and
  command scope, while observation capturing a component belongs to its lifecycle. Do not
  capture components in retained handlers. Restoration and recording are independent options.
- Decompose/Android navigation and saved-state integration belong in the application adapter.
  The machine library must not depend on a navigator or an Android lifecycle.
- Definition IDs, versions and typed-state names participate in persistence. Evaluate version
  compatibility when renaming states, reordering transitions or changing executable rules.

## Implement and verify

Follow the module's nearest working example rather than inventing constructors. Weather is
under `examples/weather/shared/src/commonMain/kotlin/actron/example/weather/`; its root/components
show how to connect reusable machines, feature command handlers and UI.

Use the smallest relevant tests and compile targets. New public Kotlin API requires the
repository's API dump/check workflow. Follow `AGENTS.md` for required checks, including the source absence guard
`./gradlew :nullability-guard:checkPolicy`; `checkNullability` additionally compiles the whole
project. This repository rejects nullable API/state shortcuts. Format changed Kotlin files
when needed, then run checks sequentially; do not format unrelated working-tree edits.

Report the resulting state/action/command flow, validation actually run and any behavior
that remains unverified. A skill does not authorize pushes, merges or deployment.
