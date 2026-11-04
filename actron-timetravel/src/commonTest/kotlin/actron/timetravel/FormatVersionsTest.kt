package actron.timetravel

import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.file.JOURNAL_FILE_FORMAT_VERSION
import actron.timetravel.file.GroupRecordingFileFormat
import actron.timetravel.file.RECORDING_FILE_FORMAT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The five on-disk formats are frozen (ADR 2026-10-01-format-freeze-policy): their versions
 * are these, and a bump fails here until the table is updated in the same change as the
 * reader of the previous version, its test and the golden.
 */
class FormatVersionsTest {
    private val frozen =
        mapOf(
            "JOURNAL_FORMAT_VERSION" to 7,
            "JOURNAL_FILE_FORMAT_VERSION" to 1,
            "RECORDING_FORMAT_VERSION" to 7,
            "RECORDING_FILE_FORMAT_VERSION" to 1,
            "GroupRecordingFileFormat.VERSION" to 3,
        )

    @Test
    fun theFormatVersions_areTheFrozenOnes_andABump_comesWithItsChecklist() {
        val current =
            mapOf(
                "JOURNAL_FORMAT_VERSION" to JOURNAL_FORMAT_VERSION,
                "JOURNAL_FILE_FORMAT_VERSION" to JOURNAL_FILE_FORMAT_VERSION,
                "RECORDING_FORMAT_VERSION" to RECORDING_FORMAT_VERSION,
                "RECORDING_FILE_FORMAT_VERSION" to RECORDING_FILE_FORMAT_VERSION,
                "GroupRecordingFileFormat.VERSION" to GroupRecordingFileFormat.VERSION,
            )

        assertEquals(
            frozen,
            current,
            "A format version changed. The freeze policy (doc/internal/adr/2026-10-01-format-freeze-policy.md) asks, in the same change: " +
                "(1) the history line at the constant; (2) explicit rejection of unsupported versions (" +
                "a branch on the header's version for a layout, a stated meaning of an absent field for the order file); " +
                "(3) the test that reads a segment or fixture of the previous version; (4) the golden of the current version re-pinned; " +
                "(5) this table.",
        )
    }
}
