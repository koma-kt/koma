# actron-observability

Use this module to answer **which Store processed which input, in what order, and what
happened afterward**. It records diagnostic boundaries for ordinary Stores and richer
decisions for Machines; it does not replay them.

Start with the [root concepts](../README.md), follow this module's quick start, and use
[Time Travel](../actron-timetravel/README.md) when typed snapshots and re-deciding inputs are
needed. The [Weather example](../examples/weather/README.md) demonstrates application recording
and inspection. Journals and replay recordings have separate contracts. Dependency availability and
composite-build setup follow [installation](../README.md#installation).

A structured journal for [Actron](../README.md) Stores: what each Store accepted, dropped,
processed, committed, emitted and failed, as records with stable identity and order, kept in a
bounded in-memory buffer and handed to sinks by one writer. It is the diagnostic layer the
[Time Travel and structured logging handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md)
builds the inspector and the replay on; this module is the journal only.

- **Source.** The `StoreProbe` of `actron-core`: every input (startup, dispatch, transaction,
  recovery of a launch failure), each processing with its outcome, each commit with its revision,
  events and reported failures, correlated to their input. A `Plugin` cannot see most of this.
- **Identity and order.** `RuntimeSessionId` (a run), `MachineGroupId` (Stores ordered together),
  `StoreInstanceId` (one instance, never a class or chat id), `GroupSeq` (dense, the order) and
  `StoreSeq` (dense per Store). Sequence numbers are assigned under one lock together with
  retention, so the order is the publication order whatever thread published.
- **Payload policy before retention.** A `PayloadPolicy` decides on the Store's thread what the
  journal keeps of a state, action, event or failure. The production default keeps metadata only;
  a failure becomes a `FailureDescriptor` (class names, causality, no message). Nothing is
  sanitized later in a sink or a UI: what the policy did not keep does not exist.
- **Session.** `RecordingSession` keeps the last N records for inspection, queues every record for
  the sinks and writes them in order from one coroutine. Loss is explicit: an overflow of the
  writer's queue is counted and the next record that fits is preceded by a `JournalGap`.
- **Sinks.** `JournalSink` receives records in order. `actron-logging` ships `LoggerJournalSink`,
  which writes `JournalFormat.line` to a `Logger`; `JournalFileSink` (below) writes the records
  to a ring of checksummed segment files that survive the process.
- **Other producers.** `session.publish(store, entry)` publishes an entry a probe cannot see,
  for a Store the session records. `actron-statechart` uses it for the decisions of a replay-ready
  machine (`DecisionCommitted`, `DecisionIgnored`, `InputRejected`), for the bridge of a
  group (`BridgeSent` with the request a reply was decided from as its `cause`,
  `BridgeReceived`, `BridgeDropped` for a message the store it was delivered to closed without
  deciding, with the message's sender and effect), for the
  effect mailbox (`EffectQueued`, `EffectHandlingStarted`, `EffectAcknowledged`, `EffectDiscarded`),
  for inputs of external sources (`ExternalReceived`), for a group's cut (`CheckpointCreated`,
  a record of the session itself through `session.publish(entry)`) and for a store closed with
  commands unfinished (`CommandsAbandoned`).

Status: **stable in the fork** since 5.0-3 (2026-10-01): all public declarations are supported without opt-in; `apiCheck` holds the API;
the module lives in the fork [roman-n1/actron](https://github.com/roman-n1/actron). The record model has `JOURNAL_FORMAT_VERSION`
7; only the current record format is accepted. Current wire bytes are pinned by golden tests;
the segment layout `JOURNAL_FILE_FORMAT_VERSION` 1; every recording is `Capability.InspectOnly`
(the replay recording is `actron-timetravel`'s).

## Dependency

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:actron-observability:1.0.0-beta.1")
            implementation("io.github.roman-n1:actron-logging:1.0.0-beta.1") // LoggerJournalSink
        }
    }
    compilerOptions {
    }
}
```

The module brings the fork's `actron-core` with it; see the [statechart README](../actron-statechart/README.md)
for the composite build setup until a release is published.

## Quick start

One session per process run, one group per screen, one `StoreInstanceId` per Store instance.

```kotlin
val session = RecordingSession(
    scope = appScope, // owns the writer coroutine; outlives every Store
    group = MachineGroupId("chat-screen"),
    config = JournalConfig(
        retainedRecords = 4_000,
        writerQueueCapacity = 4_096,
        onFailure = { failure -> crashReporter.breadcrumb(failure.toString()) },
    ),
    sinks = listOf(LoggerJournalSink(tag = "Journal")),
)

val chatPolicy = PayloadPolicy<ChatState, ChatAction, ChatEvent>(
    state = { state -> Payload.Projected(state.kindLabel(), mapOf("messages" to state.messageCount.toString())) },
    action = { action -> Payload.Projected(action.kindLabel()) },
    // events and failures keep the metadata-only defaults
)

val store = Store<ChatState, ChatAction, ChatEvent>(ChatState.Loading) {
    recordTo(session, StoreInstanceId("chat-$tabInstanceId"), chatPolicy)
    // ...
}
```

`session.records()` returns the retained records, oldest first; `session.stats` the counters
(published, retained, evicted, dropped for sinks, sink and policy failures). `session.close()`
publishes `RecordingStopped`, drains the queue and stops the writer.

What one dispatch looks like in the log, with the production policy:

```
[3f2a… chat-screen chat-7 #12/9 +1.204s] InputAccepted #4 Dispatch -
[3f2a… chat-screen chat-7 #13/10 +1.204s] ProcessingStarted #4 ordinal=4
[3f2a… chat-screen chat-7 #14/11 +1.205s] StateCommitted #4 revision=3 - <- -
[3f2a… chat-screen chat-7 #15/12 +1.205s] ProcessingFinished #4 ordinal=4 Handled commits=1 in 812us
```

`-` is an omitted payload. With `PayloadPolicy.retainAll()` (tests and local debugging only) the
objects print their `toString()`.

## Journal files

`JournalFileSink` writes the session's records into segments of a `SegmentStorage`: `KOMAJRNL`,
a header frame, record frames, an end frame, every frame `[length][crc32][payload]`. Segments
rotate by size and the oldest are deleted; a reader tells a finished segment from one a crash cut
short and never throws on damage: it returns the records that held and a mark for the rest.

```kotlin
val storage = FileSegmentStorage("$filesDir/journal")   // java.io on JVM and Android, POSIX on iOS
val fileSink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 512 * 1024, maxSegments = 8))
val session = RecordingSession(appScope, sinks = listOf(fileSink))
// on background or in a crash hook, from any thread, never waiting for a Store:
fileSink.flush()

val files = JournalFiles(storage)
files.sessions()                          // the runs present, oldest first
val contents = files.read(sessionId)      // records in order, marks where segments or frames are missing or damaged
contents.isComplete                       // nothing missing or damaged
files.tail(sessionId, 200)                // for the crash reporter, as the policy left the records
files.exportLines(sessionId)              // JournalFormat.line per record, "! mark" per mark
files.prune(maxTotalBytes = 16L * 1024 * 1024)   // the oldest sessions go whole, the newest never
```

A retained object is written as its `toString()` and reads back as `Payload.Described`; the file
holds no objects. The marks: `TruncatedTail` (a frame cut short by a crash, dropped), `Unfinished`
(no end frame, nothing lost), `Corrupt` (checksum or encoding; the rest of the segment skipped),
`MissingSegments` (rotated, pruned or lost), `SequenceHole` (records absent that no `JournalGap`
explains), `UnsupportedFormat` (a newer writer). See the
[ADR](../doc/internal/adr/2026-09-30-journal-file-format.md).

## Rules

- A policy runs under the Store lock for every trace: keep it cheap, never let it throw. If it
  does, the payload is `Payload.Unavailable`, the error goes to `JournalConfig.onFailure`, and the
  record is still published.
- Sinks run on the session's writer, never on a Store's thread. A sink that throws is counted and
  reported; nothing is written about it, so a broken sink cannot feed on its own reports. A slow
  sink delays the sinks after it and the queue, not a Store.
- Two tabs of the same chat are two `StoreInstanceId`s. Registering one id twice fails.
- The defaults of `JournalConfig` (4 000 retained records, a queue of 4 096) follow the JVM
  measurements in `JournalBudgetJvmTest`: about 120 bytes per record under the production
  policy, so about 0.5 MB each. A policy that retains payloads changes that; measure on the
  target devices with `session.stats` and set the budgets in the application.

## Files after interruption

A successful storage flush hands complete frames to the operating system; a new process can
read them after the writer is killed. Unflushed buffered bytes may be absent. A frame cut short
is discarded and reported as `TruncatedTail`; an intact segment without END is `Unfinished`.
Rotation is not atomic: the preceding segment remains readable if the next header is interrupted.
Recording and group readers return the continuous range their files still cover, for replay.

This is a **process interruption contract**, not a power-loss guarantee. JVM/Android use
`BufferedOutputStream.flush()` and iOS uses `fflush()`; neither requests `fsync`. A failure from
write, flush or close stops the affected sink and releases its handle. A disk-full failure must
be handled through the journal session's failure reporter or recording's `onFailure`; it does
not imply that queued decisions reached disk. Recording failures leave the segment without END.

`RecordingDurabilityJvmTest` kills child JVM writers at buffered/flushed frame boundaries,
inside a frame, and during rotation, then checks the files and replay in a fresh JVM. The
`Recording filesystem durability` CI job also fills an isolated 8 MiB tmpfs and verifies actual
ENOSPC for journal, member and group writers. It never fills a developer's filesystem. These
tests validate JVM process death and disk full; iOS interruption and power loss remain separate
platform validation work.
