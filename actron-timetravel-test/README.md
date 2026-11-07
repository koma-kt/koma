# actron-timetravel-test

Optional bridge from generated machine-test traces to view-only Time Travel recordings.
Keep this dependency in a test source set:

```kotlin
implementation("io.github.roman-n1:actron-timetravel-test:1.0.0-beta.1")
```

This module depends on `actron-statechart-test` and `actron-timetravel`. The generator itself
does not depend on Time Travel; ordinary model tests and production modules do not pull in
recording support. The repository's `checkDebugGraph` also prohibits production modules from
depending on this bridge.

```kotlin
import actron.timetravel.test.recording

val trace = model.record(generated.witnesses.getValue("late-response"))
val recording = trace.recording(
    schema = codec.schema,
    definitions = definitions,
    describe = { Json.encodeToJsonElement(Context.serializer(), it) },
    snapshot = { step, coverage -> displayFrame(step.after, coverage) },
    settings = HistoryExportSettings("My project"),
)
val bytes = codec.encode(recording)
```

Supply display frames and redact sensitive data in the projection and `details` transform.
The file contains before/action/after details and snapshots, including ignored inputs.
It contains no executable handlers or resumable checkpoint. Saving bytes is the host's job.

Each entry describes the before and after contexts, passes the complete details through the
host's redaction callback, then creates the display snapshot. The snapshot receives cumulative
transition coverage through that step; ignored and failed decisions do not add hits. The bridge
keeps input order, including command results and timer inputs, and uses their logical times as
offsets from `startedAt`.

See [generated scenarios and recording semantics](../actron-statechart-test/README.md#view-generated-scenarios-in-time-travel)
and [Weather's runnable example](../examples/weather/README.md#automatically-generated-tests-and-recordings).
