# actron-logging

Two ways to understand what a Store is doing: readable local Store logs, or ordered output
from a structured recording session. Logging observes work; it does not make state changes
or create a replay recording.

## Dependency

See [installation](../README.md#installation), then add:

```kotlin
implementation("io.github.roman-n1:actron-logging:1.0.0-beta.1")
```

## Simple Store logs

Add this plugin inside the `MachineStore(...) { … }` runtime configuration:

```kotlin
import actron.logging.simpleLogging

// Inside MachineStore(...) { ... }
plugin(simpleLogging(tag = "Counter"))
```

It logs actions, events and state changes using their `toString()`. With no dispatcher, log
entries run in the Store hook's order. Supplying a dispatcher moves logging into independent
coroutines: output may reorder, and pending entries can be dropped when the Store closes.
Use the default ordered mode while investigating transition order.

## Structured logs for a group

```kotlin
import actron.logging.LoggerJournalSink
import actron.observability.RecordingSession
import kotlinx.coroutines.CoroutineScope

fun recordingSession(scope: CoroutineScope) = RecordingSession(
    scope = scope,
    sinks = listOf(LoggerJournalSink()),
)
```

Attach Stores with `recordTo(session, storeId)` and Machine decisions with
`session.decisionsOf(storeId)` as explained in
[actron-observability](../actron-observability/README.md). Close the Stores, then
`session.close()` to drain its writer. The sink formats already-filtered journal records on
that writer in group order. Its default payload policy retains metadata instead of full
state/action/event contents.

| Need | Choose |
|---|---|
| Read a single Store's values while developing | `simpleLogging` |
| Correlate ordered inputs, decisions and bridge deliveries | `RecordingSession` + `LoggerJournalSink` |
| Reopen a diagnostic journal from disk | `JournalFileSink` in [observability](../actron-observability/README.md) |
| Re-decide a run and compare its outcomes | [Time Travel recording](../actron-timetravel/README.md), not text logs |

Choose payloads deliberately: simple logs contain their full string representation, while
a structured journal follows its `PayloadPolicy`. Use synthetic/debug data for verbose logs.
See [Logger](src/commonMain/kotlin/actron/logging/Logger.kt),
[logging plugin](src/commonMain/kotlin/actron/logging/Plugin.kt) and
[journal sink](src/commonMain/kotlin/actron/logging/LoggerJournalSink.kt).
