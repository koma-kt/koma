# actron-timetravel

Use this module to **reproduce a recorded Machine decision**, find the first difference and
try another input from its historical checkpoint. A replay is an offline computation, not a
live Store rewound in place. Ordinary Store logging alone cannot supply a replay recording.

## Initialization and build variants

There is no global initialization or automatic recorder. Attach observers explicitly before
starting a machine, only in the build that needs debugging. Configure bounded histories once:

```kotlin
val configuration = HistoryConfiguration(capacity = 1000) // default: 500, must be > 0
val timeline = HistoryTimeline<AppFrame>(configuration)
val recorder = BoundedMachineRecorder(machine, checkpoint, configuration)
```

Capacity counts entries, not bytes. A timeline and recorder remain separate owners; when
combining machines, trim each recorder as its global timeline entries are evicted (the Weather
adapter demonstrates this). Captures, pinned entries and external references can outlive a ring.

On Android put the inspector and recorder integration in `src/debug` and use
`debugImplementation` for the time-travel artifacts. Release should not refer to these types
or construct an observer. A visibility flag alone does not stop recording or remove code.
For production checkpoint persistence use [actron-persistence](../actron-persistence/README.md),
which is re-exported here for existing callers. See [Weather initialization](../examples/weather/README.md#concise-machine-setup)
for a complete implementation and a resolved release dependency check.

New here? Run the [Weather example](../examples/weather/README.md) before
integrating the APIs below. It demonstrates live history, preview, continuation and
view-only file import without re-running the command handler. See [installation](../README.md#installation)
for dependency setup and current release availability.

Replay for [Actron](../README.md)'s replay-ready machines: record a run of a `MachineStore`, decide
it again step by step with the pure machine, move back and forward over it, and branch off any
position with new inputs on a virtual clock. Stage 5 of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md): one
Store, in memory.

- **Recording.** `MachineRecorder` is a `DecisionObserver` of a `MachineStore`; it keeps every
  machine input in processing order with the decision it produced (or the reason it was ignored,
  or the failure). `recording()` gives an immutable `Recording`: the checkpoint it starts from
  and the steps. Every position is a checkpoint: `snapshotAt` is the machine's snapshot there,
  `checkpointAt` the executor's state (commands running, queued in their lanes, or ending with
  their last input on its way), carried forward with the same `Lanes` bookkeeping the live
  executor uses. `recording.since(store.checkpoint())` is the run from a live checkpoint on:
  what a journal keeps when its ring has dropped the beginning.
- **Replay.** `ReplaySession(machine, recording)` is a cursor: `seek`, `stepBackward` (recorded
  snapshots, no deciding), `stepForward` and `verify` (the machine decides the recorded input
  again and every field of the decision is compared). The first difference is a `ReplayMismatch`
  that names the step, the input and each field that differs. A timer recorded as firing before
  its deadline diverges too.
- **Compatibility.** A recording is `Replayable` by the same definition and version,
  `InspectableOnly` by another version of the same definition (snapshots can be shown, not
  decided), `Unsupported` by another definition. Versions are declared, never guessed.
- **Branch.** `session.branch()` continues from the current position, and
  `Branch(machine, store.checkpoint())` from where the live executor is: `dispatch` new actions,
  `advance` the virtual clock to fire timers at their deadlines, `answer`/`complete`/`fail`
  the commands that are `awaiting`, or `reuseRecordedAnswers` for a command equal to a recorded
  one (same value, same node, same lane). Commands `queued` in a lane start when the lane lets
  them, under every policy, exactly as the executor would; they cannot be answered before. No
  handler ever runs; a command with no answer stays awaiting rather than reaching any network.
  The live store is untouched, and `branch.checkpoint` is where the branch is now.

- **Codec.** `RecordingCodec` writes a recording as canonical JSON (`RECORDING_FORMAT_VERSION`,
  fields in a fixed order, maps sorted, commands sorted by id, durations in ISO-8601) with the
  application's kotlinx-serialization serializers for its context, actions, commands and events,
  and reads it back. Any other format version is `Unsupported`; a payload the serializers
  reject or an inconsistent checkpoint is `Invalid` at its position. Only the current format
  is supported; no implicit defaults or old-version migrations are applied.

- **Inspector.** `Inspector` (package `inspect`) is the read model of stage 4: the Stores of a
  group with their capabilities, the timeline of what happened in the group's order, each
  position with its input and cause, commits, decision, commands, timers, events and failures,
  the snapshots before and after with their difference when a `MachineRecorder` recording is
  attached and matches, and an explicit `Completeness` that names what the journal does not
  hold. `InspectorText` renders it as lines;
  [actron-timetravel-compose](../actron-timetravel-compose/README.md) is the Compose screen over it.

- **Groups.** `GroupRecorder` records a `MachineGroup` (see the statechart README): each member's
  recording and the order the decisions were made in across the group. `GroupReplaySession`
  steps the members in that order, `verify` checks every member and the bridge (a message
  received before it was sent, sent by nobody, delivered twice, where no route ever led, or over
  the reply route of a pair whose sender had received no request from the receiver is a
  `GroupMismatch`, as is an input from a source the group had not attached; the recording keeps
  every route the bridge had, removed ones included, with its request/reply pair), `since(cut)`
  begins at a consistent cut with its messages in flight and its sources' snapshots, and
  `GroupBranch` continues every member with a local bridge that delivers routed effects as
  they are decided and `feed(store, source, action)` for the scripted data of a source.

- **Files.** `RecordingFileSink` records a run to segments of a `SegmentStorage` as it happens,
  each segment beginning with the executor's checkpoint, rotated by size and bounded, so a
  ring that dropped the oldest ones still replays from what remains; `RecordingFiles.read`
  returns the last continuous range and marks every hole, which a replay never crosses. A
  step dropped at the very end of a run is absent, and only the sink's `stats` count it.
  `GroupRecordingFileSink` and `GroupRecordingFiles` do the same for a group: the members'
  files and an order file with the messages in flight at every segment's start. A cut of the
  group begins a segment in every file, the order segment's header carrying the sources'
  snapshots, so the run since a cut (`since(cut)`) knows them like the in-memory one.
  `RecordingFiles.prune` bounds what all the files take together. Current writer bytes are
  pinned by golden tests. Before the first release, only current formats are supported;
  see the [unreleased API baseline](../doc/internal/adr/2026-10-26-unreleased-api-baseline.md).

Replay, branches, persistence and the inspector are supported APIs without opt-in.
Only the current recording format is accepted; no old-format migration is performed.
The [Compose inspector](../actron-timetravel-compose/README.md) supports group-wide positions:
one cursor restores every member, and forward replay stops on decision or bridge/source
causality mismatches. Seeking restores recorded checkpoints; verification remains explicit.

## Dependency

```kotlin
implementation("io.github.roman-n1:actron-timetravel:1.0.0-beta.1")
```

The module brings `actron-statechart` and `actron-observability` with it; see the
[statechart README](../actron-statechart/README.md) for the composite build setup.

## Quick start

```kotlin
val recorder = MachineRecorder(listMachine, ListContext())
val store = MachineStore(listMachine, ListContext(), handler, scope = appScope, observers = listOf(recorder))
// ... the app runs ...
val recording = recorder.recording()

val session = ReplaySession(listMachine, recording)
when (val result = session.verification()) {
    ReplayVerification.Passed -> println("Recording matches")
    is ReplayVerification.Diverged -> println(result.mismatch.describe())
}
// Existing verify(from, to, onMismatch) remains supported.
session.seek(12)                         // the snapshot after twelve steps
session.stepForward()                    // Matched, Diverged or Finished at the final checkpoint

val branch = session.branch()            // what if, from here?
branch.dispatch(ListAction.Load("dogs"))
branch.awaiting                          // [Fetch("dogs")]: nothing runs, the caller answers
branch.queued                            // what waits in a lane; starts when the lane lets it
branch.answer(branch.awaiting.single().id, ListAction.Loaded(listOf("rex")))
branch.advance(10.seconds)               // fires the timers that come due
branch.effects                           // the events the branch emitted; shown, never delivered

val checkpoint = store.checkpoint()      // the live executor's state, as data
Branch(listMachine, checkpoint)          // what if, from where the app is right now
recording.since(checkpoint)              // the run from the checkpoint on: replays and branches from it
```

## Bounded in-memory recording

Use `BoundedMachineRecorder(machine, context, capacity = 500)` as a `MachineStore` observer
before starting the store. `capture()` atomically returns a `RecordingWindow` with absolute
`firstStep`/`nextStep` indices, origin status and an immutable `recording`.

The recorder keeps a ring of steps and the executor checkpoint immediately before its oldest
step. Eviction advances this checkpoint through the discarded step with the same lane/timer
bookkeeping as ordinary recordings. Ignored and failed inputs also occupy slots and advance
the boundary clock. `ReplaySession(machine, window.recording)` supports seek, verification and
isolated branches after any number of evictions. `RecordingCodec` encodes this ordinary recording
without a new file format.

`clear()` discards all steps but retains the current boundary checkpoint so subsequent inputs
still replay. `discardBefore(absoluteIndex)` allows a host with several recorders to enforce a
shared budget. Indices never restart after clear. An overload accepts an exact
`ExecutorCheckpoint` for recording from a known mid-run boundary. Unknown beginnings remain
marked through `origin`; callers must not present them as verified recordings.

Capacity must be positive and bounds **step count, not bytes**. The boundary may retain active
commands and application data. A caller that holds old captures owns those extra immutable
copies. Recorder callbacks and captures are synchronized; a capture cannot combine steps and a
boundary from different moments. The original unbounded `MachineRecorder` remains compatible.

## Inspecting a run

Generated or shrunk model-test inputs can become a recording without running a live Store:
`machine.recordInputs(initialSnapshot, scenario.inputs)` decides each input once and retains
ignored and failed steps for `ReplaySession`. For a started workflow, supply an authentic
`ExecutorCheckpoint` with unfinished command payloads instead of a snapshot. Input time must
not move backwards. This debug helper executes no commands or events; the
application owns serialization and redaction of the supplied data.

```kotlin
val inspector = Inspector.of(session, recordings = mapOf(StoreInstanceId("list-7") to recorder.recording()))
// or, after a process death: Inspector.of(JournalFiles(storage), sessionId, recordings)

inspector.stores                          // StoreView: capability, counts, recording status, completeness
inspector.completeness.isComplete         // false when anything is missing; the reasons say what
inspector.timeline                        // TimelineItem: Processing, Discarded, Pending, Rejected, Closed, Gap, Stopped, Damage, ...
val position = inspector.timeline.filterIsInstance<TimelineItem.Processing>()[12]
position.attachment.withRecording { println(it.before); println(it.after); println(it.diff) }
position.commits; position.withDecision { println(it) } // what the journal kept
inspector.replayability(StoreInstanceId("list-7")) // Available, or Unavailable with the reasons

InspectorText.overview(inspector)         // session, completeness, one line per Store with its reasons
InspectorText.timeline(inspector)         // one line per position
InspectorText.detail(position)            // input and cause, outcome, active nodes, before/after, changed, commands, timers, events
```

What one position looks like, with a recording attached:

```
#14 +1.204s list-7 Processing #4 Dispatch Load(cats) -> Handled commits=1 revision=2 in 812us transitions=[T0] commands=[c1 load/Latest Fetch(query=cats)] timers=[t1+10s] active=[Loading,Root] [recorded]
  input: Dispatch Load(cats)
  outcome: Handled commits=1
  active: Loading, Root
  before: revision=1 active=[Idle,Root] context=Ctx(query=, items=[], timeouts=0)
  after:  revision=2 active=[Loading,Root] context=Ctx(query=cats, items=[], timeouts=0) commands=[c1] timers=[t1]
  changed: context; entered Loading; exited Idle; commands +c1; timers +t1
  commands: c1 Fetch(query=cats) in load (Latest) for a3
  timers: t1 T3 at +10s
```

Without a recording the same position shows the commits as the policy kept them (`-` under
the production policy) and the Store's completeness says `payloads omitted by the policy`.

## Replaying a group

```kotlin
val group = MachineGroup(session)                       // the live side, see the statechart README
val recorder = GroupRecorder(group)
val picker = MachineStore(pickerMachine, PickerCtx(), handler, scope, observers = listOf(group.member(pickerId), recorder.member(pickerId, pickerMachine, PickerCtx())))
// ... the members run and talk ...
val recording = recorder.recording()

val session = GroupReplaySession(mapOf(pickerId to pickerMachine, rootId to rootMachine), recording)
session.verify()                          // [] or the GroupMismatches, in order of position
val pickerView = session.memberViews.getValue(pickerId)
session.seek(3)
println(pickerView.snapshot)               // always follows the group cursor
session.seek(12); session.snapshotOf(rootId)
when (val cut = group.checkpoint()) {
    is GroupCut.Ready -> GroupReplaySession(machines, recording.since(cut.checkpoint))
    is GroupCut.TimedOut -> println("Timed out at ${cut.stage}")
    is GroupCut.MemberUnavailable -> println("Unavailable member ${cut.member}")
}

val branch = session.branch(routes)       // every member from here, with a local bridge
branch.dispatch(pickerId, Pick("zed"))    // the pick, the root's apply, the acknowledgement back
```

## Recording to files

```kotlin
val storage = FileSegmentStorage("$filesDir/recordings")
val sink = RecordingFileSink(storeId, listMachine, ListContext(), codec, storage, appScope, RecordingFileConfig(maxSegmentBytes = 512 * 1024, maxSegments = 8))
val store = MachineStore(listMachine, ListContext(), handler, appScope, observers = listOf(sink))
// ... the app runs; on exit:
sink.close()

val contents = RecordingFiles(storage).read(storeId, codec)   // the last continuous range, from a checkpoint
if (contents is RecordingFileContents.Readable) ReplaySession(listMachine, contents.recording).verify { println(it.describe()) }
contents.marks                                              // MissingSegments, StepsMissing, StartMismatch, Damaged

val groupSink = GroupRecordingFileSink(group, MachineGroupId("chat"), storage, appScope)
val picker = MachineStore(pickerMachine, PickerCtx(), handler, appScope, observers = listOf(group.member(pickerId), groupSink.member(pickerId, pickerMachine, PickerCtx(), pickerCodec)))
val groupContents = GroupRecordingFiles(storage).read(MachineGroupId("chat"), codecs)
if (groupContents is GroupRecordingFileContents.Readable) GroupReplaySession(machines, groupContents.recording).verify()
```

## Serializing a recording

```kotlin
val codec = RecordingCodec(ListContext.serializer(), ListAction.serializer(), ListCommand.serializer(), ListEvent.serializer())
val text = codec.encode(recording)               // canonical JSON, format RECORDING_FORMAT_VERSION

when (val decoded = codec.decode(text)) {
    is DecodedRecording.Decoded -> ReplaySession(listMachine, decoded.recording)
    is DecodedRecording.Unsupported -> log("format ${decoded.formatVersion}: ${decoded.reason}")
    is DecodedRecording.Invalid -> log("${decoded.reason} at ${decoded.at}")
}
```

`RecordingCodecGoldenTest` pins the current writer representation, verifies round trips and
explicit rejection of unsupported format versions. There are no format migration hooks.

## Files after interruption

A successful storage flush hands complete frames to the operating system; a new process can
read them after the writer is killed. Unflushed buffered bytes may be absent. A frame cut short
is discarded and reported as `TruncatedTail`; an intact segment without END is `Unfinished`.
Rotation is not atomic: the preceding segment remains readable if the next header is interrupted.
Recording and group readers return the continuous range their files still cover, for replay.
The group reader trims member steps beyond the surviving order. If rotation removed the
checkpoint needed for a member with no entries in that range, it reports `StartMismatch` and
returns no group recording, instead of substituting a newer state.

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

All library-owned domain contracts are non-null and enforced by source/compiler checks.
See the [null-free migration guide](../doc/guides/absence-policy.md) for callbacks, lifecycle
results and UI state replacements. Old recording readers and format versions are preserved.

## Saving and restoring an executor

`machinePersistence(machine)` infers generated serializers from the machine's types. It has
no Android, Decompose, database or HTTP dependency; the application owns where and when the
returned string is stored.

```kotlin
val persistence = machinePersistence(machine)
val saved = persistence.save(store) // suspend: captures through the executor mailbox
// Persist saved using the application's storage. Close the old store when replacing it.
val restored = persistence.resume(saved, handler, scope)
restored.start()
```

`save(checkpoint)` and `restore(saved)` support hosts that already coordinate several machines.
`MachinePersistence(machine, recordingCodec)` supports custom serializers. The versioned
format includes machine identity and definition version; malformed data, incompatible definitions,
nonempty histories, invariant violations and checkpoints with unacknowledged effects or
undelivered terminal command outcomes are rejected with `IllegalArgumentException`. The host
chooses whether to migrate or start fresh. Saved data is not encrypted by this API.

Restoration does not repeat state entry hooks. Completed commands stay completed; running
commands restart from the beginning, queued commands retain their lanes, and timers preserve
their remaining delay (time without an executor is paused). A computation needs explicit saved
progress to resume partway through. A subscription is re-established and follows its source's
replay/cursor semantics; missed events are not reconstructed automatically. Database transactions
and coroutine stacks are not serialized. Writes and other side effects require idempotency or
application reconciliation because restarting a command is at-least-once execution.

The checkpoint excludes accepted-but-undecided inputs and the debug timeline. Persistence must
complete in the host's storage before it can survive process loss. Neither `resume` nor `save`
closes a previous store, and `resume` returns a store that the host must start explicitly.

### Several machines in one snapshot

Define a typed schema once, then save and restore one string:

```kotlin
val persistence = checkpointGroup(::AppFrame) {
    machine(machinePersistence(searchMachine),
        read = { it.search },
        write = { frame, checkpoint -> frame.copy(search = checkpoint) })
    machines(machinePersistence(workerMachine),
        read = { it.workers },
        write = { frame, checkpoints -> frame.copy(workers = checkpoints) })
}
val saved = persistence.save(frame)
val restoredFrame = persistence.restore(saved)
```

`machine` requires exactly one checkpoint; `machines` supports an ordered list, including
an empty list for an inactive feature. Each definition appears once in the schema and supplies
its own stable ID. Repeated instances of that definition belong in its list. Different members
may have entirely different context, action and command types; no caller casts are needed.
All checkpoint payloads are validated before calling the frame factory or any writer. Invalid
formats, missing/extra definitions, incompatible machine versions and incorrect singular
cardinality are rejected. Supply pure frame factories/writers; storage and replacement of live
executors remain the host's responsibility.

This API packages a consistent immutable frame provided by the host. It does not atomically
capture independently running stores, start executors, navigate screens or persist data by
itself. Application metadata can wrap the returned string. The group format is versioned;
changes to the member schema require application migration or an explicit fresh-start policy.

### Invoked parent and child

`InvokedMachineStore` runs an `InvokedMachine` through one mailbox and commits its parent and
child snapshot atomically. Persistence captures both executors through that same mailbox:

```kotlin
val store = InvokedMachineStore(composition, initialContext, parentHandler, childHandler, scope)
store.start()
val persistence = invocationPersistence(composition)
val saved = persistence.save(store) // suspend: one consistent cut
store.close()

val restored = persistence.resume(saved, parentHandler, childHandler, scope)
restored.start()
```

No host command or timer scheduler is needed. Resume preserves active configuration, context,
shallow/deep history, ownership, queued work and remaining timer delays. It restarts pending
commands according to each side's recovery strategy, without executing entry hooks again.
Child results are qualified by their owner, so late replies from an old child cannot reach a
replacement. Parent/child feedback settles before any command from the decision starts.
Parent events are available through `event` and `mailbox`; child events route inside the
composition. Persistence still rejects unacknowledged effects and undelivered terminal outcomes.

The pure data API remains available: `save(checkpoint)`, `restore(saved)` and
`composition.decide(checkpoint.snapshot, input)`. `InvocationCheckpoint` and `ChildCheckpoint`
now live in `actron.statechart.machine`, alongside the executor; update imports from the
previous timetravel types. The serialized format is unchanged. Both definitions and their
versions are validated, including when the child is dormant.

Use `invocation(persistence, read, write)` inside `checkpointGroup` to register the whole
composition as one member, keyed by the parent definition. Custom serializers can be supplied
through `InvocationPersistence(composition, parentCodec, childCodec)`.

Common persistence tests cover fresh and completed machines, nested hierarchy, shallow/deep
history, parallel regions with partial completion, cascading completion, and active/dormant
invocations. They serialize, rebuild definitions, restore and continue with actions, command
results or timers; invocation tests additionally check owner replacement and stale results.

### Command recovery policies

Both `MachinePersistence.resume(recovery = ...)` and invocation resume
(`parentRecovery` / `childRecovery`) accept `CommandRecoveryStrategy`. It is consulted **only for
commands that were running in the checkpoint**. Queued commands have never started and use the
normal handler; completed work does not reappear.

```kotlin
val recovery = CommandRecoveryStrategy<Request, Action> { command ->
    when (command.command) {
        is Request.Load -> CommandRecoveryDecision.Restart
        is Request.Submit -> CommandRecoveryDecision.Reconcile(checkOperationStatus)
        is Request.NeedsApproval -> CommandRecoveryDecision.AwaitDecision { envelope ->
            approval.awaitDecision(envelope.command)
        }
    }
}
```

`Restart` executes the original handler (the default). `Reconcile` executes the supplied
handler instead, using the same command identity and result sink: it can query an operation ID
and return a typed result without submitting the operation again. `AwaitDecision` suspends
until application code returns a recovery decision. It occupies the lane, is cancellable on
exit/close, and remains saveable as pending work. Saving it does not serialize the suspended
coroutine: after another restore the strategy is consulted again. Selectors and callbacks run
in the command scope; decisions/state commits remain on the processing dispatcher.

These policies are executable application configuration, not serialized lambdas. Recovery
failure uses the normal command-failure path. They do not promise exactly-once external IO:
reconciliation/idempotency still needs domain identifiers and external service support. The
existing durable outbox/receipt API remains a separate contract.

### Portable recordings for inspection

`HistoryRecordingCodec` exports display snapshots, event IDs, machine identities, wall-clock
instants and before/after details. It does not restore stores or start effects. Keep export
DTOs separate from executable checkpoints; select and redact both snapshot data and action
payloads before sharing a file.

```kotlin
val settings = HistoryExportSettings(projectName = "My application")
val definitions = listOf(machine.chart.historyDefinition(machine.id.value, machine.version.value))
val codec = HistoryRecordingCodec("my-app/display/1", DisplaySnapshot.serializer(), definitions = definitions)
val recording = timeline.recording(
    schema = codec.schema,
    snapshot = { frame -> frame.toDisplaySnapshot() },
    details = { details -> details.redacted() },
    metadata = settings.metadata(),
).copy(definitions = definitions)
val bytes = codec.encode(recording)
val imported = codec.decode(bytes)
```

`DisplaySnapshot`, `toDisplaySnapshot` and `redacted` are application-owned in this sketch.
The core has no navigation, Android, Decompose or file-picker dependency. A platform shell
reads/writes the returned bytes. `HistoryExportSettings.fileName(localTimestamp)` creates a
path-safe suggested name; the host formats the instant in the user's time zone. The JSON
metadata keeps the original project name and an absolute export instant.

Compatibility is strict: format version, application snapshot schema, explicit machine
versions and structural definitions must match. The structure includes node kinds, hierarchy,
initial states, history defaults, ordered transitions, triggers, timer delays and declared
guard/effect labels. Reordering transitions is incompatible because coverage uses their indices.
Changing reducer/guard/handler code without changing the declaration cannot be detected by this
structural comparison: **bump the machine version for semantic changes**, and bump the snapshot
schema for changes to the display DTO or its interpretation. There is no unsafe force-import.

The default limits are 8 MiB, 10,000 events and nesting depth 64. UTF-8, event order, machine
identity consistency and timestamps are validated. A ring-buffer recording is self-contained
for viewing because every retained event contains a complete display snapshot; original event
IDs and gaps are preserved. The codec is not an authenticity check or a credential scrubber.
Import into a detached viewer and replace its old recording only after full validation succeeds.

### Group recording file internals

`GroupRecordingFileSink` owns member registration, order accounting, queue admission and
writer failure/cancellation policy. Its single writer delegates segment state, rotation,
flushing and retention to `GroupOrderSegmentWriter`. Header metadata is still read when a
segment opens; queued decisions and cuts carry their original positions and messages in flight.
The sink preserves the primary storage failure when releasing an output also fails.

`GroupRecordingFileFormat` owns wire encoding and decoding, including legacy headers and
valid-prefix diagnostics. `GroupOrderHistory` reads continuity of the order stream;
`GroupRecordingRange` intersects it with member recordings, checks idle checkpoints and cuts,
and reconstructs the readable history. An unfinished segment can continue into its successor;
other damage starts a new range while retaining earlier diagnostics. These internal boundaries
do not change public APIs, file versions, bytes or replay semantics.

### Inspector internals

`Inspector` remains the public read-model facade. `InspectorTimelineBuilder` owns journal
correlation by Store and input, late processing records, payload counts and stable placement
of pending inputs around file-damage marks. Typed timeline slots keep finished positions and
processings that are still collecting records distinct.

`InspectorRecordings` aligns checkpoints and recorded steps with journal decisions. A mismatch
rejects every proposed attachment for that Store while preserving attachments of other Stores.
`InspectorSummary` derives Store views and ordered completeness reasons from the final timeline,
attachment status and journal counters. Replay availability, diagnostic text and ordering retain
their existing contracts; these components do not hold live Stores or start work.

### Replay internals

`ReplaySession` owns its cursor and calls the pure machine once per checked input. The stateless
`ReplayComparison` owns ordered timer, outcome, snapshot and intent diagnostics. Verification
stops at the first divergence without moving the cursor; failure comparison still uses type,
not message or cause details.

`GroupReplayMembers` owns the member sessions and maps their positions into the group order.
`GroupReplayCausality` checks recorded bridge/source relationships independently of machine
execution; its result is computed lazily once per group replay. Request/reply checks use what
the sender had heard when an effect was emitted. Forward stepping rejects causality errors
before deciding a member; full verification keeps member divergences before bridge diagnostics
at the same position. Existing API signatures and diagnostic ordering are preserved.

Group replay owns the group cursor. `memberViews` exposes live read-only member positions,
snapshots and checkpoints; move through the group session. The legacy `members` sessions
remain available but are deprecated: moving them independently cannot change group reads,
branches or the next group step. Request/reply causality checks the pair name and the
requesters known when the reply was emitted.

`GroupRecording` captures shallow snapshots of all constructor collections, so later changes
to the caller's maps, lists and sets cannot change validation or cached replay diagnostics.
Member recordings, source snapshots and application payloads retain their identity.
`ReplaySession.verification(from, to)` returns `ReplayVerification.Passed` or the first
`ReplayVerification.Diverged`, without moving the cursor. The callback-based `verify` retains
its existing Boolean result and callback contract.
