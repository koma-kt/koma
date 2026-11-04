# Statechart layer on top of Actron: roadmap

- Updated: 2026-10-06 (gap-analysis waves implemented; see 2026-10-06-gap-roadmap.md); 2026-10-01 (plan B dropped; full divergence inventory vs 4.0.0; version 5.0 decision; 2026-09-28: phase status after wave 6)

## Background

Actron 4.0 already works as a flat state machine over sealed classes. It has
`state<S2> { enter / action<A2> / exit / recover<T> }`, first-match in registration
order, effects with state lifetime (`launch`, `LaunchControl`),
business errors as transitions (`recover {}`) and `Plugin` for observation.

What is missing for Harel statecharts:

- hierarchy: a phase change is determined only by `state::class != nextState::class`
  (`StoreImpl.onActionDispatched` / `onStateChanged`), so a transition between
  child variants of one sealed parent does exit/enter "wholesale", without LCA;
- parallel (orthogonal) regions and history;
- a guard as a separate entity (the condition lives in the handler body);
- **a transition as data**. The handler registry in `StoreBuilder` is private
  lists of "predicate lambda + handler lambda" pairs. The types `S2` / `A2` exist
  only inside the reified predicate `it is S2` and never get out.

Because of the last point, introspection, validation,
visualization, transition coverage and model-based testing are impossible today.

## Policy

### Do not rewrite Actron

- The statechart is an optional layer in a separate module. The existing `Store` API and
  semantics do not change.
- `Actron State != statechart node`. The layer uses composition: `Store`
  remains the source of `StateFlow` for the UI, and the statechart is responsible for the structure
  of transitions.
- The statechart is applied only where there is `State + Event -> Transition`.
  Ordinary data (`toolbarTitle`, `scrollPosition`, input field values) is not
  turned into nodes.

### DSL != Runtime

```text
Statechart DSL
      |
      v
StateChartDefinition (immutable data)
      |
      +--> Validator
      +--> Exporter (Mermaid / DOT)
      +--> Test path generator
      +--> Runtime --> Actron Store adapter
```

The DSL builds an immutable model; the runtime executes it. Everything the tools
need (validation, diagrams, coverage) is taken from the model without runtime
reflection.

### Semantics before API

The hardest part is deterministic semantics: the exit/enter order by LCA,
transition priority, guard order, simultaneous transitions in regions, coroutine
lifetime, exceptions, persistence. Each phase begins with
semantics-first tests; the public API is polished last. Do not aim straight for
`StateChartDefinition<S, A, E, ...>` with many generic parameters.

Expected orders:

```text
Connected / ChatOpened -> Disconnected
  exit ChatOpened, exit Connected, enter Disconnected

Connected / Idle -> Connected / ChatOpened
  exit Idle, enter ChatOpened
```

## Phases

### Status (after wave 6)

All phases 0–7 are done in the fork; phase 8 partially. Numbers are PRs in `roman-n1/actron`.

| Phase | What | PR | Status |
|---|---|---|---|
| 0 | Architectural contract (this document) | #1, #6 (fork path) | merged |
| 1–2 | Model, validation, Mermaid | #5 | merged |
| 3 | Flat runtime | #7 | merged |
| 8 (part) | Conformance plugin; shortest paths and coverage paths | #8, #9 | merged |
| 5 | Hierarchy (compound, LCA) — wave 1 | #10 | merged |
| 7 | History (shallow/deep) — wave 2 | #11 | merged |
| 6 | Parallel regions — wave 3 | #12 | merged |
| — | Timers `Trigger.After` — wave 4 (there was no phase for it in the original plan) | #13 | merged |
| 4 | `StateChartStore` adapter — wave 5 | #14 | merged |
| — | "Messenger" example as a test, `actron-statechart/README.md`, comparison with actron-strict — wave 6 | #15 | merged |

Core steps 1–3 from the upstream strategy are #2, #3, #4 (merged into the fork). The semantics and decisions of each
wave are in [`2026-09-28-statechart-semantics.md`](./2026-09-28-statechart-semantics.md), sections
"Wave 1–5 decisions". Phase 4 was done last rather than fourth: the adapter is easier to write once the
semantics of hierarchy, regions, history and timers are already fixed.

Phase 8 now includes named invariants, executable bounded exploration, seeded random walks,
state/transition/guard/timer coverage and deletion shrinking of a failing sequence. The
[six-wave gap follow-up](2026-10-06-gap-roadmap.md) also adds explanations, automatic transition
semantics, durability/migrations, scoped child composition and the typed DSL/diagram.
The debug timeline, group replay and replay/experiment workflow are implemented, with a
[runnable disk-to-inspector example](https://github.com/roman-n1/actron/blob/bb173b88/examples/time-travel/README.md) added on 2026-10-03.

### Phase 0 — architectural contract

This document. No code.

### Phase 1 — introspectable model

A new KMP module `actron-statechart` (modeled on `actron-test`: android, iosArm64,
iosSimulatorArm64, jvm). In the first iteration, one module with the packages `model`,
`validation`, `tooling`, `runtime`, `dsl` instead of six separate artifacts.

Minimal model: `StateId` (value class), `StateNode` / `AtomicState`,
`Transition(source, target, on: ActionMatcher)`, `StateChartDefinition`.
`ActionMatcher` carries a `KClass<out Action>` and a stable name, because
`qualifiedName` is not available on all KMP targets. The dependency on `actron-core`
is only for the marker interfaces. Unit tests.

### Phase 2 — validation and Mermaid

Duplicate IDs, transitions to nonexistent states, unreachable states,
missing initial, conflicting transitions without guards. Export to Mermaid
`stateDiagram-v2`.

### Phase 3 — flat runtime

A pure function `step(configuration, action) -> TransitionResult` with metadata
of the transition that fired. No coroutines and no Actron.

### Phase 4 — integration with Actron

An adapter that assembles a `Store` from the definition. The preferred variant, without
core changes: a single catch-all `state<S> { action<A> { runtime.step(...) } }`.
The Actron state keeps the active configuration as a field. The transition trace for
coverage is captured through `Plugin` and `actron-test` (`dispatchAndAwait`,
`StoreRecorder`).

### Phase 5 — hierarchy

Compound states, initial transitions, the LCA exit/enter algorithm. If the layer keeps
the Actron state as a single class, Actron itself does no exit/enter, and then the
coroutine lifetime of substates is managed by the layer.

If this runs into limitations, the minimal hook in the core is a "phase key"
strategy `(S) -> Any` (default `it::class`) instead of `state::class` in
`StoreImpl`, marked `@ExperimentalActronApi`. It touches
`stateRuntimes`, `PendingActionPolicy` and `LaunchControl`, so only through a
separate ADR.

### Phase 6 — parallel regions

`ParallelState` / `Region`, the configuration as a set of active leaves,
a deterministic order of region processing.

### Phase 7 — history

Shallow / deep history.

### Phase 8 — tooling

Path generation for model-based testing, transition coverage, invariant
checking, shrinking of a failing sequence, debug timeline.

## Definition of Done for the first milestone (Phase 1–2)

- the existing Actron API is unchanged;
- the model is immutable, transitions are first-class objects, state IDs are stable;
- the model can be traversed in full;
- a flat graph can be described and its unreachable states found;
- the Mermaid export is built without runtime reflection;
- there are unit tests.

## Upstream strategy: small steps

The goal is for the Actron author (`koma-kt/koma`) to accept changes one at a time. For
that, each step:

- is useful to Actron on its own, even if the statechart layer never appears;
- builds on what the author has already written down in `doc/internal/`;
- is additive and does not change the behavior of existing Stores;
- starts as a feature request and only after agreement turns into a
  small PR.

Order and placement:

| # | Step | Where | Benefit for Actron on its own | What it gives the statechart layer |
|---|---|---|---|---|
| 1 | Keep matcher metadata (`StateType(S2::class)` / `AnyState`, `ActionType(A2::class)` / `AnyAction`) next to the predicates in the `StoreBuilder` registry. The public API does not change. | core, internal | The basis for the routing diagnostics the author proposes in `notes/2026-04-25-unhandled-action-behavior.md` (the section on `build()` checks names exactly this as the obstacle) | The first data about the structure: which state and action types are declared |
| 2 | Routing diagnostics in `:actron-test`: `diagnoseActionMatches`, asserting the number of matches on dispatch | `actron-test` | Exactly items 2–3 from the same note by the author | Checking that a transition in the model and a handler in the Store agree |
| 3 | A read-only description of all handlers (`describeHandlers()`); in the core only an `@InternalActronApi` method, the public API in `actron-test`, because the author prefers not to widen the public surface of `actron-core` | `actron-test` + core internal | Store documentation, handler coverage in tests | Coverage checking and a first visualization without our own DSL |
| 4 | The reason for a state change, for observers (action / enter / launch transaction / recover) | core, contentious | Debug timeline and trace (upstream #189), `receiveTransition` in the test driver (upstream #176) | Transition metadata for coverage and MBT |
| 5 | The `actron-statechart` module: model, validation, Mermaid, path generation | separate module (in the fork) | — | Phases 1–2 and part of Phase 8 |
| 6 | Runtime and adapter to Store through the ordinary DSL | separate module | — | Phases 3–4 |
| 7 | Opt-in hierarchical state scopes: `enter` / `exit` / `launch` of a sealed parent survive transitions between its child variants (LCA over the sealed hierarchy) | core, RFC + ADR | Today `state<Parent> { enter {} }` restarts on every change of child variant, which is inconvenient even without statecharts. Shaped as a policy enum, following `doc/internal/adr/2026-05-07-runtime-policy-enum.md` | Phase 5 without our own coroutine management |
| 8 | Parallel regions, history | separate module | — | Phases 6–7, not proposed for the core |
| 9 | RFC "optional introspectable statechart model for Actron": the module as a companion or an external artifact | upstream issue | — | Official status for the layer |

Notes on risks:

- Step 4 contradicts the author's current position: in
  `notes/2026-05-02-plugin-design.md` a Plugin observes only the Store's boundaries
  (input and output), and there is no separate hook for a type change. So step 4 comes
  after 1–3 and is proposed as data, not as a new hook. If the author
  declines, the layer correlates `onAction` → `onState` itself (under one mutex) or
  provides the metadata from its own runtime.
- Step 7 changes scope semantics, so only opt-in and only after a
  separate ADR.
- Steps 5, 6 and 8 require nothing from upstream and proceed in the fork in parallel
  with 1–4.
- The upstream issue numbers (#175, #176, #189) are taken from the handoff and the author's
  notes and were not verified in this session.

### Plan B (dropped 2026-10-01)

Roman's decision (2026-09-28) was to send only the request for step 1 upstream and to keep
`actron-statechart` on the public API of Actron 4.0.0, so that the layer would work on unmodified
Actron. Dropped on 2026-10-01: the layer requires the fork's `actron-core`. It uses the
`@InternalActronApi` bridge (`StoreInternalApi.dispatchIf`, `StoreBuilder.validateRecovery`), the
`StoreProbe` that `MachineStore`'s idle gate and `actron-observability` are built on, the public
`InputId`, and `StoreScope` opened for the chart scopes; `actron-logging` depends on
`actron-observability`. So the fork is published as one set of modules under `io.github.roman-n1`,
and the way back to upstream is per row of the divergence inventory below, not per module.

Order of work (2026-10-01): messenger-facing changes first; upstream changes are prepared in
parallel as small steps and sent without waiting for the author's answer to earlier ones.

Draft of the first feature request (step 1):
[`notes/2026-09-28-upstream-fr-handler-matcher-metadata.md`](../notes/2026-09-28-upstream-fr-handler-matcher-metadata.md);
the series that follows it: [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md).

## Using the fork in the messenger and the way back to upstream

Roman's goal: connect the fork `roman-n1/actron` to his KMP messenger now,
and return to official Koma as the author accepts the requests. The rules
follow from this.

### Rules for the fork

- **Additive, one step per PR.** `actron-core` changes in the fork are additive and each is its own
  commit, so a row of the inventory can be replaced by upstream's version of the same change or
  dropped on its own.
- **The statechart layer only in its own modules.** `actron-statechart`, `actron-observability`,
  `actron-timetravel`, `actron-timetravel-compose`, `actron-statechart-test` and
  `actron-statechart-compose` are additive modules; they are not divergence, and they are built and
  published only with the fork's `actron-core`.
- **The inventory is complete.** The tables below are the complete list of what the fork changed in
  the modules upstream owns. A row leaves when upstream ships the same change (on the next tag
  merge) or when the fork removes it; both happen in the PR that does the merge or the removal.
- **Fork-only means fork-only.** A row marked `fork-only by design` is not proposed upstream; its
  "removable when" names the alternative that would let the fork drop it.

### Divergence inventory vs upstream 4.0.0

2026-10-25: the supported application API is now StateChart-only. The standalone `Store(...)`
factories were replaced by the internal `createRuntimeStore` bridge; `StoreBuilder` requires
`InternalActronApi`. The coroutine `StateChartStore` adapter and its configuration/scopes moved
to commonTest regression fixtures and are excluded from production artifacts. Public consumers
use `Machine` / `MachineStore` and `retainedMachine`; no compatibility aliases or alternate
handwritten reducer contract remain. Message sending no longer requires an engine DSL scope.
See [the API decision](../adr/2026-10-25-statechart-only-api.md) and published-consumer compile
contracts for the intentional source/ABI break. Earlier rows describe the historical runtime work.


Columns: what changed; where (files, symbols); since (stability-review round, commit); the
regression tests that pin it; upstream status (`not proposed`, `prepared: upstream-pr/<topic>`,
`issue #N`, `PR #N`, `merged in <tag>`, `declined`, `fork-only by design`); removable when.
The rounds are sections of the [stability review](../notes/2026-09-29-stability-review.md); the upstream series and its
status live in [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md).

#### (a) Stability fixes in `actron-core`

Tests are in `actron-core/src/commonTest/kotlin/actron/core/` unless marked JVM (`src/jvmTest`).
KDoc that documents these contracts (`Plugin.kt`, `Store.kt`, `StateSaver.kt`, `ExceptionHandler.kt`,
`StoreScope.kt`) travels with the row it belongs to.

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| a1 | `state.first()` / `take(n)` no longer hang: collected in the caller's coroutine, startup requested after the first value | `StoreImpl.state.collect` | [round 1](../notes/2026-09-29-stability-review.md#fixed), 80b2018 | `StoreRegressionTest.stateFirst_returnsOnceThePredicateMatches` | prepared: `upstream-pr/state-first-hang` @ 481ae37 (U1) | merged |
| a2 | A throwing `exit {}` keeps the state's runtime; `ClearOnStateExit` clears pending actions before the commit | `processStateExit`, `commitTransition` | round 1, 80b2018 | `StoreRegressionTest.exitException_recoveredInPlace_keepsTheStateRuntimeUsable`, `clearOnStateExit_keepsActionsDispatchedByPluginsForTheNewState` | prepared: `upstream-pr/exit-failure-keeps-runtime` @ 7968e5c (U2) | merged |
| a3 | Actions are processed in dispatch order on multi-threaded dispatchers; a handler leaving a child coroutine behind does not stall later dispatches | `launchDispatch`, `lastDispatchDone`; `Store.dispatch` KDoc | [round 2](../notes/2026-09-29-stability-review.md#fixed-in-the-second-round), 4d4edcd; [round 5](../notes/2026-09-29-stability-review.md#fixed-in-the-fifth-round), 4e92b04 | `StoreMultiThreadedTest.dispatches_areProcessedInDispatchOrder`, `StoreRegressionTest.aHandlerLeavingAChildCoroutineBehind_doesNotStallLaterDispatches`, `StoreSoakJvmTest.dispatchJobs_areNotRetainedThroughTheOrderingChain` (JVM) | planned: `upstream-pr/dispatch-order` (U4) | merged |
| a4 | A cancelled launch never commits, emits or recovers, also from `NonCancellable` cleanup; nested transactions are rejected | `canRunLaunchOperation`, both launch scopes' `transaction`/`event` | round 2, 4d4edcd; [round 7](../notes/2026-09-29-stability-review.md#fixed-in-seventh-round-second-stability-review-wave), f08dcd4 | `StoreRegressionTest.cancelPrevious_skipsATransactionQueuedByTheCancelledLaunch`, `StoreLaunchCancellationTest` (five tests) | planned: `upstream-pr/cancelled-launch-transaction` (U5) | merged |
| a5 | A failing `StateSaver.save`, `onState` or `onEvent` is reported and the transition finishes | `processStateChange`, `processEventEmit`, `reportWithoutAborting` | round 2, 4d4edcd; round 5, 4e92b04 | `StoreRegressionTest.stateSaverException_onVariantChange_stillEntersTheNewState`, `pluginOnStateException_onVariantChange_stillEntersTheNewState`, `pluginOnEventException_doesNotAbortTheEmittingHandler` | planned: `upstream-pr/observer-failure-continues-transition` (U7) | merged |
| a6 | Plugins' `onStart` runs once, only failed hooks retry; completed launches release their lanes | `processPlugins(starting)`, `startedPluginIndices`, `trackActionLaunch` | round 2, 54b1ed6; [round 6](../notes/2026-09-29-stability-review.md#fixed-in-the-sixth-round), 90cafce | `StorePluginExceptionTest.pluginOnStartException_retriesInitializationOnNextDispatch`, `StoreLaunchRetentionJvmTest` (JVM) | not proposed | merged or declined |
| a7 | A `CancellationException` from an expired timeout is a handler failure; a failing initial `enter {}` counts as started; `recover {}` keeps the original error as suppressed; an enter chain longer than 500 states fails instead of looping | `rethrowIfNonRecoverable`, `onErrorOccurred`, `MAX_ENTER_CHAIN` | [round 3](../notes/2026-09-29-stability-review.md#fixed-in-the-third-round), 5366bd9; round 5, 4e92b04 | `StoreRegressionTest.expiredWithTimeout_*`, `failedInitialEnter_isReportedOnceAndTheStoreProcessesActions`, `recoverThatThrows_keepsTheOriginalErrorAsSuppressed`, `enterLoop_failsWithAnErrorInsteadOfOverflowingTheStack`, `enterLoopGuard_leavesTheStoreInAUsableState`, `aFailingEnterOfTheRecoveredToState_keepsTheOriginalErrorAsSuppressed`, `pluginHookTimingOut_failsTheActionUnderBothPolicies` | planned as an issue only (U8: a semantic choice) | merged or declined |
| a8 | Plugin hook rounds never overlap; fast path for zero or one plugin | `pluginMutex` | round 3, 3dbaf71 | `StoreMultiThreadedTest.pluginHookRounds_neverOverlap_evenForEventsEmittedFromLaunches` | planned: `upstream-pr/serialized-plugin-rounds` (U6) | merged |
| a9 | Every plugin sees every round even when one fails; a fatal error stays fatal | `hookFailure` | [round 4](../notes/2026-09-29-stability-review.md#fixed-in-the-fourth-round), 916c19c; round 5, 4e92b04 | `StoreRegressionTest.aFailingPluginHook_doesNotHideTheRoundFromOtherPlugins`, `aFatalErrorFromASecondPlugin_staysFatal` | planned: `upstream-pr/every-plugin-every-round` (U6b) | merged |
| a10 | `dispatchAndAwait` / `startAndAwait` from inside the Store's own handler or `onEvent` round fail fast instead of deadlocking | `InsideStore` context element, `checkNotInsideThisStore` | round 4, 26832ec; round 5, 4e92b04 | `StoreRegressionTest.awaitingTheStoreFromInsideItsOwnHandler_failsFastInsteadOfDeadlocking`, `awaitingTheStoreFromAnOnEventRoundOfALaunchedEvent_failsFast` | planned with the `awaitIdle` issue | merged |
| a11 | Nothing commits after `close()`; `recover {}` does not run after close; an exit failing after close does not recover | `commitTransition` (`ensureActive`), `onErrorOccurred` | round 4, 916c19c; round 6, 90cafce; [round 8](../notes/2026-09-29-stability-review.md#fixed-in-the-eighth-round-whole-project-review-after-the-sixth-and-seventh), ff28f1c | `StoreCloseJvmTest.nothingCommitsAfterClose` (JVM), `StoreShutdownRegressionTest.closeDuringNonCancellableExitDoesNotCommitOrSaveTheTransition`, `StoreRegressionTest.anExitFailingAfterClose_doesNotRunRecover` | prepared: `upstream-pr/no-commit-after-close` @ ce615da (U3) | merged |
| a12 | `patch {}` is rejected once startup is requested; the exception handler is not called twice for one error | `isStartupRequested`, `InternalError.reported` | round 6, 90cafce | `StoreStartupRegressionTest.patchIsRejectedAsSoonAsStartupIsRequested`, `patchIsRejectedAfterPartialPluginStartup`, `StoreStartupExceptionJvmTest` (JVM) | not proposed | merged or declined |
| a13 | A launch failing while its state exits reaches the `ExceptionHandler` | `executeLaunchInStateRuntime` | round 8, ff28f1c | `StoreRegressionTest.aLaunchFailingWhileItsStateExits_reachesTheExceptionHandler` | not proposed (may join U5) | merged or declined |
| a14 | Soak tests that back the fixes above without mapping to one | — | round 5 | `StoreSoakTest`, `StoreSoakJvmTest` (JVM) | travel with the rows | — |
| a15 | A fatal `ProcessingStarted` probe or a rethrowing probe exception handler still restores processing, releases pending work and emits `ProcessingFinished`; machine input waiters terminate | `StoreImpl.process` | admission/shutdown stabilization, 2026-10-06 | `StoreProbeFailureJvmTest` (JVM), `AdmissionShutdownJvmTest.*ProcessingStartProbe*` (JVM) | not proposed | merged or declined |

#### (b) Observation hooks in `actron-core` (all `@InternalActronApi`)

The 2026-10-11 [absence-policy migration](../../guides/absence-policy.md) is a deliberate
fork-only API break: Store factories use overloads, execution overrides use non-null contexts,
`StateSaver.restore` receives a fallback, launch lanes select a key explicitly, and Store patches
contain configuration commands. Core saver/lifecycle/patch/launch tests, Compose retained-state
tests and persistent-restore tests cover the changes. Consumers must recompile; historical
inline-constructor compatibility claims below describe the earlier API. The migration is complete: the baseline is empty and a strict marker protects zero debt.
Kotlin PSI plus a compiler IR plugin reject explicit and inferred nullable types across every
library Main target; old recording readers and frozen format versions are preserved.

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| b1 | `StoreProbe`: every accepted, discarded and processed input with its outcome, commits, events and failures; `currentInputId()`; input ids carried in the coroutine context | `StoreProbe.kt` (`StoreProbe`, `StoreTrace`, `InputKind`, `DiscardReason`, `ProcessingOutcome`), `InputOrigin` in `StoreProcessor.kt`, `StoreBuilder.probe`, `StorePatchBuilder.probe`, `StorePatch.probes` | [round 9](../notes/2026-09-29-stability-review.md#fixed-in-the-ninth-round-time-travel-foundation-probes-journal-machine-executor), ccc1c1e, 2e1331a, 90a7e02, 1ccdabd | `StoreProbeTest` | planned: issue "StoreProbe", then `upstream-pr/store-probe` (a) and `upstream-pr/store-probe-correlation` (b) | merged; used by `actron-observability` (journal); `MachineStore` correlates admission reservations and per-input waiters with terminal traces, and observes the completion of close |
| b2 | Matcher metadata next to the handler predicates; `StateHandler` and `ThreadedHandler` are internal implementation types with non-null inputs, without upstream 4.0.0 constructor-compatibility promises | `HandlerMatcher.kt`, `StoreBuilder.StateHandler.matcher`, `ThreadedHandler.inputType` | ae8f313 (roadmap step 1) | `StoreHandlerRegistryTest`, `StoreHandlerRegistryPropertyTest` | issue koma-kt/koma#280 (open, no answer); prepared: `upstream-pr/handler-matcher-metadata` | merged |
| b3 | `StoreInternalApi.matchActionHandlers` and `diagnoseActionMatches` in `actron-test` | `StoreInternalApi.kt`, `actron-test/ActionMatchDiagnostics.kt` | 8a03bda (step 2) | `ActionMatchDiagnosticsTest` | not proposed until #280 lands | merged or declined |
| b4 | `StoreInternalApi.handlerMetadata` and `describeHandlers` in `actron-test` | `StoreInternalApi.kt`, `actron-test/StoreHandlers.kt` | 48af6ac (step 3) | `StoreHandlersTest` | not proposed until #280 lands | merged or declined |
| b5 | `StoreInternalApi.dispatchIf(action, isValid)`: the predicate runs under the lock before `onAction`, so an activity's action queued while its node exited is discarded as stale | `StoreInternalApi.kt`, `StoreImpl.kt`; used at `StateChartStore.kt` (`ChartLaunchScope.dispatch`) | round 6, 90cafce | `StoreProbeTest.dispatchIf_withAFalsePredicate_isDiscardedAsStale_andConsumesNoOrdinal`, `StateChartActivityDispatchTest` | fork-only by design | an `ActivityDispatch(action, activation)` envelope through plain `dispatch()`, unwrapped by the chart's `action<Action>` handler, replaces it |
| b7 | `StoreInternalApi.awaitIdle(timeout): StorePendingWork` and the `pendingInputs` counter next to the input traces; `StateRuntime.subscriptions` | `StoreInternalApi.kt`, `StoreImpl.kt` | 2026-10-01, [ADR](../adr/2026-10-01-await-idle-and-subscriptions.md) | `StoreAwaitIdleTest` | planned: issue on the author's note `2026-05-10-await-launch-completion-in-tests.md`, then `upstream-pr/await-idle` | merged |
| b6 | `StoreBuilder.validateRecovery {}`: a `recover {}` from `store {}` blocks may not change the chart's configuration or timers, nor a machine's snapshot | `StoreBuilder.kt`, `StoreHandlers.validateRecoveredState`; used at `StateChartStore.kt` and `MachineStore.kt` | round 6, 90cafce | `StateChartRecoveryRegressionTest` context, timers, activity and rollback tests | fork-only by design | resolved 2026-10-05: constrained ChartStoreConfiguration.recover<T> changes context only; MachineStore takes StoreConfiguration without recovery; validator retained as internal defense |

#### (c) Public API of the modules upstream owns

The 2026-10-26 build tooling update uses Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21,
Compose Multiplatform 1.12.2 and Maven Publish 0.37.0. Authored library contracts and
recording formats remain unchanged; the serialization compiler now emits `Any` rather
than `Any?` bounds on the generated `HistoryRecord.$serializer` and
`HistoryRecording.$serializer` types, reflected in the time-travel klib API dump.
Kotlin also adds the default no-argument JVM constructor of `DurationProfile`.
The time-travel Compose JVM module name is pinned to preserve its generated accessor names.
Compose's Android artifacts require compile SDK 37; the weather app keeps target SDK 36
as a separate catalog value, and the published consumer receives compile SDK from the same catalog.
The JS Yarn lock records Kotlin's updated build tooling dependencies.
Compose UI modules declare executable web binaries so browser tests bundle the Skiko runtime
with webpack, as required by Compose 1.12's test configuration checks.
Weather's Wasm target constrains Ktor's transitive kotlinx-io to 0.9.1, whose module loader
fixes the raw `import.meta` incompatibility with the updated webpack.
The fork's nullability IR audit now uses unified parameters/arguments in Kotlin 2.4;
its compiler fixtures also run against Gradle's embedded Kotlin 2.4.10.

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| c1 | `InputId` (public; `@ExperimentalActronApi` until 5.0-3, 2026-10-01), the id every journal record and decision observer carries | `StoreProbe.kt` | round 9, 90a7e02 | `StoreProbeTest` | proposed with b1 (as `@InternalActronApi`) | merged |
| c2 | `StoreScope` changed from `sealed` to open, so `ChartHookScope` and `ChartLaunchScope` can be `StoreScope`s for `actron-message`'s `message()` | `StoreScope.kt` | round 4, 916c19c | — | fork-only by design | resolved 2026-10-05: chart scopes expose `val store: StoreScope` and delegate; core marker remains open to preserve stable consumer compatibility |
| c3 | `StorePatch.probes`: a new property, so the data class's constructor and `copy` changed | `StorePatch.kt` | round 9, ccc1c1e | `StoreProbeTest` | with b1 | merged |
| c4 | New abstract members of `StoreInternalApi` (`dispatchIf`, `matchActionHandlers`, `handlerMetadata`): a binary change for implementors of the internal interface | `StoreInternalApi.kt` | rounds 6, 9 | — | with their rows | with their rows |
| c5 | Documented guarantee: actions are processed in dispatch order | `Store.dispatch` KDoc | round 2 | a3 | with a3 | merged |
| c7 | `subscribe {}` on `EnterScope` and `ActionScope` (a launch `awaitIdle` leaves out); `StorePendingWork` (public data); actron-test `awaitIdle()`, `pendingWork()` | `StoreScope.kt`, `StoreInternalApi.kt`, `actron-test/StoreExtensions.kt` | 2026-10-01 | `StoreAwaitIdleTest`, `StoreAwaitIdleExtensionTest`, `StateChartAwaitIdleTest` | with b7 | merged |
| c6 | `api/` dumps and `apiCheck` in CI; `checkDebugGraph`; CI for every pull request; `iosArm64` compiled in the macOS job; every test task logs failed assertions | root `build.gradle.kts`, `.github/workflows/gradle.yml` | fork build | — | fork-only by design | never |
| c8 | Null-free public/core contracts and internal runtime lifecycle: fallback savers, non-null contexts, typed attribution, binding/launch/work phases, configuration commands, no nullable handler selections | `actron-core` factories, `StateSaver`, `StorePatch`, `StoreProbe`, `StoreBuilder`, `StoreImpl`; absence-policy guide | 2026-10-11 | Core saver/lifecycle/patch/launch/probe suites; strict compiler fixtures and full KMP Main compilation | fork-only breaking API; consumers recompile | upstream adopts the same null-free contracts |
| c9 | Strict zero nullable/optional policy: empty baseline, immutable-against-base strict milestone, PSI and resolved/inferred IR type checks on all library Main targets | Root Gradle, `verification/nullability-guard`, `.actron/nullability`, required JVM CI policy step | 2026-10-11 | PSI self-tests, real compiler acceptance/rejection tests, every platform Main compile | fork-only by design | never |

#### (d) Companion modules upstream owns

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| d1 | `actron-compose`: the narrowed `ViewStore` of `stateContent` keeps the last `S2`, so a callback after the state's type changed does not throw | `ViewStore.kt` (`NarrowedState`, `narrow`) | 84b0000, 54b1ed6 | `ViewStoreJvmTest.stateContent_callback*` (JVM) | prepared: `upstream-pr/compose-state-content-narrowing` @ bf047ec (C1) | merged |
| d9 | `actron-compose`: real Android Activity recreation, screen removal/return, keyed instances, Store replacement and external ownership; dedicated emulator CI | `androidDeviceTest/ActivityLifecycleTest.kt`, `.github/workflows/android-compose-lifecycle.yml` | 2026-10-05 | `ActivityLifecycleTest` (Android device) | fork-only test infrastructure; no runtime API change | retained as regression coverage |
| d2 | `actron-test`: `diagnoseActionMatches`, `describeHandlers`; `StoreRecorder` refuses a second Store; a clearer error from `createRecorder()` after startup; fail-fast documented | `ActionMatchDiagnostics.kt`, `StoreHandlers.kt`, `StoreRecorder.kt`, `StoreExtensions.kt` | 8a03bda, 48af6ac, 26832ec, 4b15358 | `StoreRecorderTest`, b3/b4 tests | b3/b4 after #280; the recorder fixes with a10 | merged or declined |
| d7 | `actron-test`: a cursor on `StoreRecorder` (`receiveEvent<E>()`, `receiveEvent(predicate)`, `unconsumedEvents`, `assertNoUnconsumedEvents()`) and `Store.assertNoPendingWork(recorder?)` | `StoreRecorder.kt`, `StoreExtensions.kt` | 2026-10-01, [ADR](../adr/2026-10-01-recorder-cursor-and-pending-work.md) | `StoreRecorderCursorTest` | planned with b7 (`upstream-pr/await-idle`) | merged |
| d8 | Actron uses distinct packages, artifacts and core klib identity; consumers must recompile; upstream Koma 4.0.0 interoperability is unsupported | `actron-core/build.gradle.kts`, migration guide, published-consumer graph check | 2026-10-11 | Published consumers verify Actron artifacts on JVM/Android/JS/Wasm/iOS and reject upstream Koma dependencies | independent API and releases; legacy coexecution fixture removed | fork-only |
| d3 | `actron-logging`: `simpleLogging` logs from the hook when no dispatcher is given, so entries stay in order; a throwing logger is reported instead of aborting the action | `Plugin.kt` | bbcc6e6, 54b1ed6 | `LoggingOutputTest` | prepared: `upstream-pr/logging-inline-entries` @ 9d41c49 (C3) | merged |
| d4 | `actron-logging`: `LoggerJournalSink`, and with it a dependency on `actron-observability` (and so on b1) | `JournalSink.kt`, `actron-logging/build.gradle.kts` | round 9, 2e1331a | `LoggerJournalSinkTest` | fork-only by design | `LoggerJournalSink` moves to a `actron-observability-logging` module |
| d5 | `actron-message`: the subscription is registered before `onStart` returns; a throwing receive block is reported and the subscription continues; `extraBufferCapacity = 64` and the hub exposed as a `SharedFlow` | `Plugin.kt`, `Message.kt` | c9a11d0, 4e92b04, cb525ea | `MessageDeliveryTest` | prepared: `upstream-pr/message-subscription-before-start` @ 26a5685 (C2); the buffer size is mentioned, not proposed | merged (the buffer stays fork-only) |
| d6 | Build: `api` instead of `implementation` for `actron-core` in the companion modules; Mocha timeouts for JS and Wasm on Node; the publish convention for `io.github.roman-n1`; explicit JVM 11 bytecode baseline; isolated Maven consumer verification repository | `*/build.gradle.kts`, `build-logic` | fork build | `verification/published-consumer`: POM/metadata/payload checks, external JVM smoke and platform compile matrix | `api` prepared: `upstream-pr/companion-api-deps` @ e8d889c (C4); the rest fork-only | C4 merged; the rest never |
| d10 | Non-null companion runtime/test APIs: retained state and recorder binding phases, non-null logging contexts, observation callbacks and overloads for pending-work checks | `actron-compose`, `actron-logging`, `actron-test` | 2026-10-11 | Existing Compose retained-state, recorder/handler, logging suites; strict source/compiler guard | fork-only breaking API | upstream adopts the same contracts |

| c9 | Internal Store responsibility split: input accounting/outcomes, plugin rounds, activation launch lanes and DSL handler/launch scopes move to cohesive owners; enter/action transactions share one atomic execution path; dispatch ordering/cancellation, runtime configuration, root execution lifetime, startup phases and idle observation have separate owners; Store facade/companion API, locked processing, transition/recovery algorithm, publication, state-launch/root-input bridge and failure reporting are separated; frozen executable registrations and builder/runtime collection isolation prevent retained-builder mutations; completed subscriptions are released, event-hook failures retain launch origin, and closed-store idle waits for root completion including cleanup; public API and ordering/cancellation contracts are unchanged | `StoreImpl.kt`, `StoreInputs.kt`, `InputProcessing.kt`, `StorePlugins.kt`, `StateRuntime.kt`, `StoreFailure.kt`, `StoreStateScopes.kt`, `StoreLaunchedScopes.kt`, `StoreScopeExecution.kt`, `StoreDispatchQueue.kt`, `StoreConfiguration.kt`, `QueuedStoreInput.kt`, `StoreBuilder.kt`, `StoreExecution.kt`, `StoreStartup.kt`, `StoreIdleWaiter.kt`, `HandlerMatcher.kt`, `StoreProcessor.kt`, `StoreTransitions.kt`, `StoreTransitionEffects` (in `StoreTransitions.kt`), `StoreReporting.kt`, `StoreHandlers.kt`, `StoreCompanionApi.kt`, `StorePublication.kt`, `StoreLaunchedWork.kt`, `HandlerRegistry.kt`; adapted local agent skills and Detekt quality lane | 2026-10-24 | Core KMP regression suites, `StoreOwnershipRegressionTest`, `StoreScopeSnapshotTest`, `StoreQueueConfigurationTest`, `StoreExecutionOwnershipTest`, `StoreTransitionBoundaryTest`, `StorePublicationBoundaryTest`, `StoreLaunchedWorkBoundaryTest`, `StoreBuilderBoundaryTest`, `StoreFailureProtocolTest`, `RoutingGenerationSnapshotTest`, `StoreSubscriptionRetentionJvmTest`, `StoreEventOriginTest`, `StoreCloseCompletionTest`, independent trace invariants, isolated JVM reporting failures, synchronized coroutine/retention suites, API/ABI check | fork maintenance; no upstream proposal made | upstream adopts equivalent ownership boundaries |

### Synchronization with upstream

- The fork's `main` = upstream + our merged PRs. The upstream base is recorded in
  `gradle.properties` (`actron.upstream.base`), not in the fork's version.
- On every upstream release: `git fetch upstream --tags`, then merge the release tag
  into the fork's `main` **with a merge commit** (no rebase, so as not to break
  branches and clones). Before the merge, read `git diff <base>..<tag> -- actron-core/src/commonMain/kotlin/actron/core/StoreImpl.kt`
  in full and sort its hunks against the inventory: where upstream covers a row, take upstream's
  version and keep our test; the row leaves in the same PR. The fork's tests are the oracle of the
  merge (`:actron-core:jvmTest`, `StoreProbeTest`, the statechart tests, `apiCheck`), not the shape of
  our code.
- Practice for core (updated 2026-10-24): keep responsibilities with their data in internal
  collaborators (`StoreInputs`, `StorePlugins`, `StateRuntime`, `InputProcessing`, `StoreStateScopes`, `StoreLaunchedScopes`, `StoreDispatchQueue`, `StoreConfiguration`, `StoreExecution`, `StoreStartup`, `StoreIdleWaiter`, `StoreProcessor`, `StoreTransitions`, `StoreReporting`, `StoreHandlers`, `StoreCompanionApi`, `StorePublication`, `StoreLaunchedWork`, `HandlerRegistry`); review those
  files together with `StoreImpl.kt` during upstream merges. Public contracts and regression
  tests remain the merge oracle; do not accumulate every addition at the end of StoreImpl;
  `git config rerere.enabled true` and `merge.conflictStyle zdiff3` in the checkout; unmerged
  `upstream-pr/*` branches are re-created on the new tag.
- After the merge: `apiDump`, `actron.upstream.base` updated, the fork's CI green on all platforms,
  then a new fork version.

### How the messenger connects the fork

1. **During active development — composite build.** The fork is added as a
   git submodule, and `includeBuild("actron")` is written into the messenger's `settings.gradle.kts`.
   Gradle substitutes the fork's modules for the
   `io.github.koma-kt:*` dependencies by itself; nothing needs publishing, and edits to
   Actron are visible immediately. Downside: the messenger's CI has to fetch the submodule.
2. **Once the version has stabilized — GitHub Packages.** Publishing from the fork's GitHub
   Actions on a macOS runner (otherwise the iOS artifacts cannot be built), to the fork's own
   Maven repository. Reading requires a token with `read:packages`.
   - Coordinates: a separate group `io.github.roman-n1`, so that the fork's artifacts
     are never confused with the official `io.github.koma-kt`.
   - Version: the fork's own line, `5.0.0-alpha.N` from 2026-10-01 (decision below); the upstream
     base it was merged with is `actron.upstream.base` in `gradle.properties`, not part of the version.
   - The official `actron-core` and the fork's must not be mixed in one project:
     the classes are the same, the groups differ. Actron's group and version are set in the
     messenger's version catalog in one place.
   - The current `.github/workflows/publish.yml` publishes to Maven Central on
     prerelease and will fail in the fork without the secrets. The fork needs a separate
     publishing workflow; to be done when we get to item 2.
3. **Moving to official Koma.** When upstream has released all the core steps
   that the messenger needs: in the messenger's version catalog the group of
   `actron-core`/`actron-compose`/`actron-test` changes to `io.github.koma-kt`, and the
   version to the official one. `actron-statechart` remains our artifact (or
   moves to upstream through the RFC, step 9). Until every inventory row the layer needs is
   `merged` or removed, the messenger stays on the fork's `actron-core` (plan B is dropped).

### Version 5.0 (decision 2026-10-01)

The fork is its own line: `5.0.0-alpha.1` from this decision on (`actron.fork.version`). The number
says what the inventory says, that the modules are not "4.0.0 plus patches": `StoreScope` is open,
`StorePatch`'s constructor changed, `InputId` is new, and the statechart, journal and replay modules
are the product. `alpha` says what is still true: most of that API is `@ExperimentalActronApi` and the
on-disk formats moved with every stage. `5.0.0` without a suffix requires: (1) `@ExperimentalActronApi`
removed from the core of the machine and the journal (`Machine`, `MachineStore`, `Lanes`,
`ExecutorCheckpoint`, `MachineGroup`, `RecordingSession`, `JournalEntry`, `JournalFiles`,
`RecordingCodec`, `RecordingFiles`), held by `apiCheck`; (2) the formats frozen under a migration
policy: a version bump only with a migration or a reader of the old version and a golden of it.
Both are in place since 2026-10-01: (2) the [format freeze policy](../adr/2026-10-01-format-freeze-policy.md)
and `FormatVersionsTest`, which pins the five versions and names the checklist on a bump; (1) per
the [stable core ADR](../adr/2026-10-01-stable-core.md): `InputId`, the journal, the pure chart
and machine models, their snapshots/decisions/checkpoints, and the recording formats of
`actron-timetravel`. The sources' contract, evolving Store adapter scopes and raw StoreBuilder
configuration remain experimental; so do replay, branches and the inspector. The c2/b6
adapter changes below must resolve their public contract before those markers come off.
What remains for `5.0.0` is the tag (5.0-4).
The messenger pilot, device measurements and publishing remain work, not conditions of the number.

## Remaining work

1. **Publishing.** Set up: `.github/workflows/publish.yml` publishes every module to Maven Central
   as `io.github.roman-n1:<module>:5.0.0-alpha.1` from a GitHub pre-release, and the publish convention
   sets each project's group and version to the same coordinates, so a composite build
   (`includeBuild`) substitutes them (the build files keep `io.github.koma-kt` to stay close to
   upstream). No release has been published yet, so the messenger still connects the fork with a
   composite build; `actron-statechart/README.md` describes both.
2. **Known limitations of wave 5** (decisions in the semantics document, "Wave 5 decisions"):
   - after restore from `StateSaver`, enter hooks are not run again, and timers start with the full
     delay (the remaining time is not saved);
   - events emitted by the hooks of a failed step are not retracted; a timer whose step failed is
     removed from `timers.running` and does not fire until the node is entered again;
   - a restored configuration is checked against the definition: active nodes the chart cannot
     produce fall back to the initial configuration (keeping the context), an unrestorable history
     record is dropped;
   - `enter {}`/`action {}` for `ChartState` from `store {}` do not work, `initialState` there is
     ignored, `PendingActionPolicy.ClearOnStateExit` does not affect chart steps;
   - conformance does not see guards or clocks: self-loop timers are not counted (wave 4), and of two
     transitions with the same leaves before and after (for example, two timers `Backoff → Connecting`
     with different guards in the messenger example) the first is counted.
3. **Model.** No eventless (completion) transitions, final states, internal
   transitions or `invoke`. The first candidate is completion transitions: with them "the work
   is finished" is described without an artificial action.
4. **Phase 8 (implemented 2026-10-06).** Invariants, bounded executable exploration,
   guard/state/transition/timer coverage and shrinking. See the six-wave gap follow-up above.
   The debug timeline and group replay/experiment inspector are implemented; the
   [desktop example](https://github.com/roman-n1/actron/blob/bb173b88/examples/time-travel/README.md) demonstrates the complete file
   workflow. Real messenger wiring and device measurements remain separate work.
5. **Bridge to actron-strict** — optional, as a separate module; design in
   [`2026-09-28-actron-strict-comparison.md`](./2026-09-28-actron-strict-comparison.md).
6. **Upstream.** The series in [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md): companion fixes, the stability fixes one by one, the `awaitIdle` and `StoreProbe` proposals; steps 2–3 and the RFC (step 9) after the author's answer on #280.

## Notes

- The handler registry, first-match and the commit order (exit → `_state` + `StateSaver`
  → `Plugin.onState` → clear pending → enter) were verified against the 4.0.0 sources.
- `Plugin.onAction` is called before the handler, `Plugin.onState(prev, next)` after the
  commit. The Plugin does not know which action caused the transition; the layer provides that
  metadata itself.

## Related

- [Statechart layer semantics](./2026-09-28-statechart-semantics.md)
- [Comparison with actron-strict](./2026-09-28-actron-strict-comparison.md)
- [Module README](../../../actron-statechart/README.md)

- [Actron design principles](./2026-04-23-design-principles.md)
- [Store surface design notes](./2026-04-29-store-api-design.md)
- [LaunchControl API design](../adr/2026-05-01-launch-control-case-naming.md)


Lifecycle API follow-up (2026-10-24): `StoreLifecycle<S, E>` and core extensions add cancellable
state/event observation plus closeAndJoin without adding members to Store or changing its old
signatures. Core and both machine adapters implement the capability. Root ownership markers
reject self-join before shutdown, including NonCancellable and overridden/patched machine contexts.
Machine adapter collectors retain their existing execution contexts and event sources. Coverage:
StoreObservationTest, StoreCloseAndJoinTest, StoreLifecycleAdapterTest; JVM/klib API dumps retain
all old declarations and add only the lifecycle interface and extension functions.

Decompose follow-up (2026-10-24): `actron-decompose` supplies lifecycle-bound observation, with
retention delegated to Essenty getOrCreateCloseable. Core remains independent of Decompose. The
new observeEvent registers before returning to prevent startup events overtaking the subscription;
legacy collectEvent retains its scheduling. StoreObservationTest in core and in the adapter covers
readiness, recreation with a shared keeper, owner shutdown and cancelled/dead lifecycle observers.

### 2026-10-28 — State-local Kotlin behavior DSL

Actron adds `behaviorStateMachine`, `state<T>`, `on<Action>`, suspend `onEnter` and
`transitionTo(state = ...)` on the existing StateChart decision engine. Native executors own
entry jobs; snapshots persist pending activation identities and recording format 7 persists
entry outcomes. Weather's five machines use this API. See
[the lifecycle and diagnostics decision](../adr/2026-10-28-behavior-dsl.md).

The owner's readonly-state refinement makes both behavior scopes expose the complete immutable
`val state`. All replacements use `transitionTo(state = ...)`; `phaseOf` selects graph identity
without a public write lens. Weather uses full state snapshots and explicit phase fields.
