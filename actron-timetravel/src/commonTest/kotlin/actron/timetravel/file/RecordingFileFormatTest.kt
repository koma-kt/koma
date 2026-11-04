package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentMark
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.timetravel.RECORDING_FORMAT_VERSION
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import actron.timetravel.RecordingCodec
import actron.timetravel.carriedPast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The segment layout of a recording file: a segment round-trips its header, its checkpoint and
 * its steps, the bytes of a small one are pinned, a newer format is refused, and every cut of a
 * segment yields a prefix of its steps with a mark, never an exception and never a step that
 * was not written.
 *
 * ```
 * [*] --> Idle
 * Idle --Inc / inc--> Idle
 * ```
 */
class RecordingFileFormatTest {
    @Serializable
    data class Ctx(
        val n: Int = 0,
    )

    @Serializable
    sealed interface Act : Action {
        @Serializable
        @SerialName("Inc")
        data object Inc : Act
    }

    @Serializable
    sealed interface Ev : Event

    @Serializable
    data object NoCommand

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val machine =
        Machine<Ctx, Act, NoCommand, Ev>(
            DefinitionId("counter"),
            DefinitionVersion("1"),
            StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Act.Inc>("Inc"), effect = actron.statechart.EffectKey("inc")))),
        ) { effect("inc") { c, _ -> c.copy(n = c.n + 1) } }

    private val codec = RecordingCodec(Ctx.serializer(), Act.serializer(), NoCommand.serializer(), Ev.serializer())
    private val store = StoreInstanceId("counter-1")

    private fun steps(count: Int): List<RecordedStep<Ctx, Act, NoCommand, Ev>> {
        var snapshot = machine.initialSnapshot(Ctx())
        val inputs = listOf(MachineInput.Start(MachineTime.Zero)) + (1..count).map { MachineInput.Dispatch(Act.Inc, MachineTime((it * 10).milliseconds)) }
        return inputs.map { input ->
            val decision = machine.decide(snapshot, input)
            snapshot = decision.snapshot
            RecordedStep.Committed<Ctx, Act, NoCommand, Ev>(input, decision)
        }
    }

    private fun segment(
        steps: List<RecordedStep<Ctx, Act, NoCommand, Ev>>,
        index: Int = 0,
        firstStep: Int = 0,
        finished: Boolean = true,
    ): ByteArray {
        val start = Recording<Ctx, Act, NoCommand, Ev>(machine.id, machine.version, machine.initialSnapshot(Ctx()), emptyList()).start
        var bytes = RecordingFileFormat.header(machine.id, machine.version, store, index, firstStep) + RecordingFileFormat.checkpointFrame(codec.encodeCheckpoint(start))
        for (step in steps) bytes += RecordingFileFormat.stepFrame(codec.encodeStep(step))
        if (finished) bytes += Framing.END
        return bytes
    }

    @Test
    fun aSegment_roundTripsItsHeaderCheckpointAndSteps() {
        val steps = steps(3)

        val decoded = RecordingFileFormat.decodeSegment("counter-1-000000.recording", segment(steps), codec)

        assertEquals(RecordingSegmentHeader(RECORDING_FILE_FORMAT_VERSION, RECORDING_FORMAT_VERSION, machine.id, machine.version, store, 0, 0), decoded.header)
        assertEquals(machine.initialSnapshot(Ctx()), decoded.start?.snapshot)
        assertEquals(steps, decoded.steps)
        kotlin.test.assertFalse(decoded.ending.withIssue { error("Unexpected segment issue: $it") })
        assertTrue(decoded.finished)
        assertEquals("counter-1-000007.recording", RecordingFileFormat.segmentName(store, 7))
        assertEquals(store to 7, RecordingFileFormat.parseSegmentName("counter-1-000007.recording"))
        kotlin.test.assertFalse(RecordingFileFormat.parseSegmentName("counter-1-000007.journal") { _, _ -> error("Unexpected parsed segment") })
    }

    @Test
    fun theGoldenSegment_isTheseBytes() {
        val bytes = segment(steps(1))

        assertEquals(GOLDEN, bytes.toHex(), "the layout changed; if intended, bump RECORDING_FILE_FORMAT_VERSION.\nACTUAL:\n${bytes.toHex()}")
        assertEquals(steps(1), RecordingFileFormat.decodeSegment("golden", GOLDEN.fromHex(), codec).steps)
    }

    @Test
    fun aNewerFormat_isRefused_notGuessed() {
        val newer = RecordingFileFormat.header(machine.id, machine.version, store, 0, 0, RECORDING_FILE_FORMAT_VERSION + 1, RECORDING_FORMAT_VERSION) + Framing.END
        val decoded = RecordingFileFormat.decodeSegment("newer", newer, codec)
        assertEquals(SegmentMark.UnsupportedFormat("newer", RECORDING_FILE_FORMAT_VERSION + 1, RECORDING_FORMAT_VERSION), decoded.mark)
        assertTrue(decoded.steps.isEmpty() && decoded !is DecodedRecordingSegment.Unreadable)
        val newerSteps = RecordingFileFormat.header(machine.id, machine.version, store, 0, 0, RECORDING_FILE_FORMAT_VERSION, RECORDING_FORMAT_VERSION + 1) + Framing.END
        assertIs<SegmentMark.UnsupportedFormat>(RecordingFileFormat.decodeSegment("x", newerSteps, codec).mark)
    }

    @Test
    fun everyCut_ofASegment_yieldsAPrefixOfTheSteps_andAMark() {
        val steps = steps(4)
        val bytes = segment(steps)
        var unfinished = 0
        for (length in 0 until bytes.size) {
            val decoded = RecordingFileFormat.decodeSegment("cut", bytes.copyOf(length), codec)
            assertEquals(steps.take(decoded.steps.size), decoded.steps, "cut at $length")
            assertTrue(!decoded.finished, "cut at $length")
            when (val mark = decoded.mark) {
                is SegmentMark.Unfinished -> unfinished++
                is SegmentMark.TruncatedTail -> assertEquals(decoded.steps.size, mark.recordsRead, "cut at $length")
                is SegmentMark.NotASegment -> assertTrue(length < RecordingFileFormat.MAGIC.size, "cut at $length")
                is SegmentMark.Corrupt -> assertTrue(decoded.steps.isEmpty() && decoded !is DecodedRecordingSegment.Readable, "cut at $length: $mark")
                else -> throw AssertionError("cut at $length: $mark")
            }
            if (decoded.steps.isNotEmpty()) assertTrue(decoded is DecodedRecordingSegment.Readable, "cut at $length")
        }
        assertEquals(steps.size + 1, unfinished, "one cut per frame boundary after the checkpoint")
    }

    @Test
    fun aFlippedByte_isCaught_andNeverYieldsAWrongStep() {
        val steps = steps(3)
        val bytes = segment(steps)
        for (offset in bytes.indices) {
            val damaged = bytes.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor (1 shl (offset % 8))).toByte()
            val decoded = RecordingFileFormat.decodeSegment("bit", damaged, codec)
            assertEquals(steps.take(decoded.steps.size), decoded.steps, "flip at $offset")
            assertTrue(decoded.ending.withIssue {} || decoded.steps.size == steps.size, "flip at $offset went unnoticed")
        }
    }

    @Test
    fun carriedPast_isWhatTheSegmentsBeginWith() {
        val steps = steps(2)
        var checkpoint = Recording<Ctx, Act, NoCommand, Ev>(machine.id, machine.version, machine.initialSnapshot(Ctx()), emptyList()).start
        for (step in steps) checkpoint = checkpoint.carriedPast(step)
        assertEquals(Recording<Ctx, Act, NoCommand, Ev>(machine.id, machine.version, machine.initialSnapshot(Ctx()), steps).checkpointAt(steps.size), checkpoint)
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val GOLDEN: String = "4b4f4d415245434400000079c5c8ea79017b22666f726d617456657273696f6e223a312c227265636f7264696e67466f726d6174223a372c22646566696e6974696f6e223a22636f756e746572222c2276657273696f6e223a2231222c2273746f7265223a22636f756e7465722d31222c22696e646578223a302c22666972737453746570223a307d000000cf563b6351027b22736e617073686f74223a7b227265766973696f6e223a302c22616374697665223a5b5d2c22686973746f7279223a7b7d2c22636f6e74657874223a7b7d2c2261637469766174696f6e73223a7b7d2c22636f6d6d616e6473223a7b7d2c2274696d657273223a7b7d2c22636f756e74657273223a7b2261637469766174696f6e73223a302c22636f6d6d616e6473223a302c2274696d657273223a302c2265666665637473223a307d2c2270656e64696e67456e7472696573223a7b7d7d2c226e6f77223a2250543053227d000001c5cc638943037b22696e707574223a7b2274797065223a227374617274222c226e6f77223a2250543053227d2c22636f6d6d6974746564223a7b22736e617073686f74223a7b227265766973696f6e223a312c22616374697665223a5b2249646c65222c22526f6f74225d2c22686973746f7279223a7b7d2c22636f6e74657874223a7b7d2c2261637469766174696f6e73223a7b2249646c65223a322c22526f6f74223a317d2c22636f6d6d616e6473223a7b7d2c2274696d657273223a7b7d2c22636f756e74657273223a7b2261637469766174696f6e73223a322c22636f6d6d616e6473223a302c2274696d657273223a302c2265666665637473223a307d2c2270656e64696e67456e7472696573223a7b7d7d2c227472616e736974696f6e73223a5b5d2c22657869746564223a5b5d2c22656e7465726564223a5b7b226e6f6465223a22526f6f74222c226964223a317d2c7b226e6f6465223a2249646c65222c226964223a327d5d2c22636f6d6d616e6473223a5b5d2c2263616e63656c6c656453636f706573223a5b5d2c2274696d6572735363686564756c6564223a5b5d2c2274696d65727343616e63656c6c6564223a5b5d2c2265666665637473223a5b5d7d7d000001ea0572fc2b037b22696e707574223a7b2274797065223a226469737061746368222c226e6f77223a225054302e30313053222c22616374696f6e223a7b2274797065223a22496e63227d7d2c22636f6d6d6974746564223a7b22736e617073686f74223a7b227265766973696f6e223a322c22616374697665223a5b2249646c65222c22526f6f74225d2c22686973746f7279223a7b7d2c22636f6e74657874223a7b226e223a317d2c2261637469766174696f6e73223a7b2249646c65223a332c22526f6f74223a317d2c22636f6d6d616e6473223a7b7d2c2274696d657273223a7b7d2c22636f756e74657273223a7b2261637469766174696f6e73223a332c22636f6d6d616e6473223a302c2274696d657273223a302c2265666665637473223a307d2c2270656e64696e67456e7472696573223a7b7d7d2c227472616e736974696f6e73223a5b305d2c22657869746564223a5b7b226e6f6465223a2249646c65222c226964223a327d5d2c22656e7465726564223a5b7b226e6f6465223a2249646c65222c226964223a337d5d2c22636f6d6d616e6473223a5b5d2c2263616e63656c6c656453636f706573223a5b325d2c2274696d6572735363686564756c6564223a5b5d2c2274696d65727343616e63656c6c6564223a5b5d2c2265666665637473223a5b5d7d7d0000000000000000"
    }
}
