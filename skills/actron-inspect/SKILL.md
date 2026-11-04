---
name: actron-inspect
description: Extract and interpret Actron graph diagnostics, transition coverage, recordings, replay differences, checkpoints and causal traces. Use to explain machine behavior or investigate a reproducible bug.
---

# Inspect Actron machines

Find the checkout/version and identify the machine's stable definition ID/version, the input,
and whether the user is viewing live state or an immutable historical snapshot. Use only
artifacts provided or authorized for this task. Recordings may include application payloads;
inspect them locally and avoid putting raw payloads into public issues or logs.

## Choose the evidence

- **Allowed behavior:** inspect the actual typed machine definition and
  `actron-statechart/README.md`. Use declaration identities, not display strings, to correlate
  nodes and transitions. Behavior DSL graphs are conservative source/target envelopes, not
  exact reachability; use recorded execution to substantiate actual paths. Check `Analysis.kt`, `Validation.kt`, and `GraphDiagnostics.kt` when
  present. `chart.diagnostics` is version-dependent; older branches expose `validate` and
  bounded `analyzeReachability` instead. Prefer bounded analysis for large parallel graphs.
- **Why an input was ignored or selected:** read the real `Decision` / `DecisionExplanation`
  and runtime diagnostics. Do not re-run a guard against a different snapshot and call that
  the original reason. Static analysis cannot prove arbitrary Kotlin predicates.
- **What actually executed:** read recorded transition IDs and coverage. Keep current active
  state, selected arrow and coverage distinct. Missing coverage does not prove unreachability.
  Check whether counts are session-wide, retained-window-only or attached to each snapshot.
- **Before/after and reproduction:** read `actron-timetravel/README.md`, then the matching
  recording codec and replay APIs. `ReplaySession.seek` views saved state; verification and
  continuation have different semantics. Check definition/version compatibility first.
- **Unfinished work:** inspect executor checkpoints (commands, timers, scopes, queues and
  pending effects), not only context. Consult recovery policies before claiming a command
  will restart, reuse a result or be skipped. Never silently replay a real external write.
- **Cross-machine causality:** use group recordings or `actron-diagnostics` causal traces.
  Ordinary log timestamps alone do not establish causal order. Optional exporter adapters
  belong in their backend modules, not the core model.

## Inspector and artifacts

Read `actron-timetravel-compose/README.md` for history/graph controls. Weather adapts graph data
and screen restoration in `examples/weather/debugTools/src/commonMain/kotlin/actron/example/weather/app/debug/`; Decompose navigation remains
application-owned. Inspect the selected event's graph/configuration rather than live state.
If available, Weather's graph diagnostic demo modifies a diagram copy only; do not run a
broken demo definition as the production machine.

For machine-readable review artifacts, inspect `BehaviouralArtifacts.kt` in
`actron-statechart-test` and `verification/behaviour-review`; use the version's existing export
functions rather than inventing a JSON schema. They can provide definition, source links,
coverage, graph and transition-matrix data. This tooling is not a runtime dependency.

Present the specific input, affected state/transition identities, observed decision and
supported explanation. Separate structural errors, behavioral warnings, incomplete analysis,
and hypotheses. For UI changes verify issue selection, history isolation and viewport behavior;
physical trackpad/mobile gestures require an actual device check before claiming they work.
