# State-local Kotlin behavior DSL

- Date: 2026-10-28
- Status: implemented; validation recorded with the change

The owner requested one explicit transition operation and ordinary Kotlin conditions/delays.
`behaviorStateMachine` produces the existing `Machine`, not an alternative reducer or Store.
The explicit graph DSL remains available for complete static transition descriptions.

`on<Action>` is synchronous, transactional and called once. Its `val state` is the complete
immutable snapshot captured before the handler; calling `transitionTo` does not mutate that view.
A handler emits events and proposes at most one complete replacement with `transitionTo(state = ...)`.
There is no mutable context or state setter in the behavior scopes. `phaseOf` selects the graph
node from the proposed snapshot; no write lens is needed. Failed handlers publish no partial data.
Self-transitions exit/re-enter and receive a fresh activation.

`onEnter` is suspend work outside the processing lock. The executor owns its Job and cancels it
on exit or close. Outcomes return through the same ordered machine queue; activation checks reject
late outcomes. The entry scope exposes the same read-only full `state`, transition requests, events, and
an adapter to the existing typed command handler. It never exposes a mutable Store.
Ordinary coroutine delay does not block event processing or a UI thread. Synchronous blocking
I/O still requires an appropriate dispatcher.

Snapshots store unfinished entry records, not closures or coroutine continuations. Resume starts
unfinished work again from its beginning using saved context. Completed entry work is not repeated.
History replay applies recorded results and performs no I/O. Per the owner's follow-up, Weather
keeps debounce timing entirely inside the entry coroutine: resume starts a fresh 350 ms delay.
Feature state stores no debounce deadline. HTTP restarts with a fresh timeout. External writes
need application-level idempotency; at-least-once restart is not exactly-once delivery.

The chart's guarded source/target envelope is conservative. Arbitrary Kotlin control flow prevents
exact static reachability and automatic guard mutation. Tests exercise the real queued executor
with virtual time and explicit observations. The explicit chart DSL retains its static tooling.

Compared approaches:
- Koma ties launched work to state lifetime, but exposes launch/transaction/nextState nesting:
  https://github.com/koma-kt/koma
- KStateMachine distinguishes entry callbacks from concurrent `asyncScopedAction`:
  https://kstatemachine.github.io/kstatemachine/pages/states/states.html
- VisualFSM declares source/target types explicitly for graph construction:
  https://github.com/Kontur-Mobile/VisualFSM

Recording JSON version 7 adds pending entry records and entry result inputs. Current-format
roundtrips and goldens are updated. Per the unreleased API baseline, earlier development formats
are rejected rather than migrated. Binary segment framing is unchanged; its golden payload changes
because it embeds recording JSON. Machine definition versions change where behavior changes.

## Validation (2026-10-29)

- JVM tests passed for statechart, statechart-test, persistence, timetravel,
  timetravel-compose, Weather shared and Weather debugTools (including Compose UI scenarios).
- StateChart JS Node, Wasm Node and iOS Simulator Arm64 tests passed.
- Weather Android debug APK and macOS distributable built; the macOS app opens its city screen.
- `ktlintCheck`, `detekt`, `:nullability-guard:checkPolicy`, `apiCheck` and
  `checkDebugGraph` passed without expanding baselines.
- Review added entry-output codec round trips and reproduced an opaque JSON-null context
  failure before fixing it. Explicit null payloads now remain distinct from missing fields.
