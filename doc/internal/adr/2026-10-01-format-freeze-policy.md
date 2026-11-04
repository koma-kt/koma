# The five on-disk formats are frozen: a version bumps only with a reader of the old version, its golden and a test

> Recording format 7 adds state-owned entry work; see [the behavior DSL decision](./2026-10-28-behavior-dsl.md).
> The pre-release compatibility and experimental boundary is superseded by [the unreleased API baseline](./2026-10-26-unreleased-api-baseline.md).


- Updated: 2026-10-01

## Background

The fork writes five formats: the journal's record model and its segment layout
(`actron-observability`), the recording's JSON and its segment layout, and the group's order file
(`actron-timetravel`). Each has a version constant and moved with every stage of the handoff; the
messenger will keep journals and recordings across app versions, so a reader must read what an
earlier writer wrote, or say that it cannot, and never guess. `5.0.0` without a suffix requires
this policy (roadmap, "Version 5.0"); this ADR states it and `FormatVersionsTest` holds it.

## Decision

| Constant | Where | Versions | Reads an older version by |
|---|---|---|---|
| `JOURNAL_FORMAT_VERSION` (7) | `actron.observability.Record.kt` | the record model: variants and fields of `JournalEntry` | the decoder reads a segment by the version in its header: variants are added only at the end under new tags, fields only at the end of a variant, so an older segment decodes with the fields it has |
| `JOURNAL_FILE_FORMAT_VERSION` (1) | `actron.observability.file.JournalFileFormat` | the segment layout: magic, frames, header | a branch on the header's version keeps decoding the old layout |
| `RECORDING_FORMAT_VERSION` (7) | `actron.timetravel.RecordingCodec` | the JSON of a recording | an explicit `FormatMigration` from the previous version in the codec's own list; an older version without one is `Unsupported`, never defaulted |
| `RECORDING_FILE_FORMAT_VERSION` (1) | `actron.timetravel.file.RecordingFileFormat` | the segment layout of a recording file | a branch on the header's version |
| `GroupRecordingFileFormat.VERSION` (3) | `actron.timetravel.file.GroupRecordingFiles.kt` | the order file's header and entries (JSON) | a field added since is absent in an older segment and its absence has a stated meaning (no cut, no pair); a change of meaning needs a branch on the version |

Rules, for every one of them:

1. **A reader refuses a newer version and never guesses.** The header stays readable, so the
   refusal says which version it met (`SegmentMark.UnsupportedFormat`, `DecodedRecording.Unsupported`).
2. **A reader reads every earlier version**, by migration, by a branch on the version, or by
   an absence with a stated meaning. Silently filling a missing value with a default is not
   reading: the meaning of the absence is written at the format and tested.
3. **A bump ships with, in the same change:** the history line at the constant (what the
   version added or changed); the reader of the previous version; the test that reads a
   segment or fixture of the previous version (`JournalFileFormatTest.aSegmentOfAnEarlierRecordFormat_stillReads`,
   `RecordingCodecGoldenTest.olderFormats_areMigratedByTheCodecItself_toTheSameRecording`,
   `GroupRecordingFilesTest.anOrderSegment_roundTrips_andEveryCutIsAPrefix`, and the layout
   tests' `aNewerFormat_isRefused_notGuessed`); the golden of the current version re-pinned
   (`JournalFileFormatTest.theGoldenSegment_isTheseBytes`, `RecordingCodecGoldenTest.theGoldenFixture_isTheCanonicalJsonOfTheRecording`,
   `RecordingFileFormatTest.theGoldenSegment_isTheseBytes`); and the table in
   `FormatVersionsTest`, whose failure names this list.
4. **The wire vocabulary is stable across versions of the types it mirrors**: enumerations are
   written as their names (`DiscardKind`, `OutcomeKind`, `EffectPolicy.name`, `PairRole`),
   never class names; renaming a Kotlin type does not bump a format, renaming a name does.
5. **A version is not bumped for an addition the reader already reads** (a new tag at the end,
   a new optional field of the order file) only when the earlier reader would also read it;
   since an earlier reader refuses a newer *version* but not a newer *tag*, additions bump too:
   the version says what a reader must know, not what a writer used.

Not adopted:

- One version for all five formats: they change at different times and are read by different
  code; a bump of the journal must not invalidate recordings.
- Defaults as migration (`ignoreUnknownKeys`, missing fields filled in): a recording read with
  a guessed value replays a run that never happened.
- Migrations for the journal file: the journal is read, never rewritten; a reader of the old
  layout is enough.

## Notes

- Tests: `FormatVersionsTest` (actron-timetravel, sees all five constants): the five numbers are
  the frozen ones; a bump fails it with the checklist above until the table is updated in the
  same change, which is when the rest of the list is reviewed.
- This is criterion (2) of `5.0.0` (roadmap "Version 5.0"); criterion (1), the experimental
  marker off the core of the machine and the journal, is track 5.0-3.

## Related

- [Journal file format](./2026-09-30-journal-file-format.md)
- [Recording files](./2026-09-30-recording-files.md)
- [Request/reply](./2026-10-01-request-reply.md) (the first field appended to a variant)
- [Statechart roadmap](../design/2026-09-28-statechart-roadmap.md), "Version 5.0"
