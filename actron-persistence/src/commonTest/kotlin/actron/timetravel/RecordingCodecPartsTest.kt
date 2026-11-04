package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.ActivationId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EntryOutput
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.stateMachine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingCodecPartsTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Empty : Event

    private val codec = RecordingCodec(Int.serializer(), Tick.serializer(), String.serializer(), Empty.serializer())
    private val definition = DefinitionId("parts")
    private val version = DefinitionVersion("1")

    @Test
    fun everyEntryOutcomeRoundTripsThroughTheStepCodec() {
        val outputs: List<EntryOutput<Tick>> = listOf(EntryOutput.Completed, EntryOutput.Dispatch(Tick), EntryOutput.Transition(7), EntryOutput.Emit(Empty))
        outputs.forEach { output ->
            val step = RecordedStep.Ignored<Int, Tick, String, Empty>(MachineInput.EntryResult(ActivationId(1), output, MachineTime.Zero), IgnoreReason.StaleCommand)
            assertEquals(step, codec.decodeStep(codec.encodeStep(step), definition, version))
        }
    }

    @Test
    fun entryTransitionAcceptsAnOpaqueJsonNullContextButRejectsAMissingPayload() {
        val serializer =
            object : JsonTransformingSerializer<Int>(Int.serializer()) {
                override fun transformSerialize(element: JsonElement): JsonElement = JsonNull

                override fun transformDeserialize(element: JsonElement): JsonElement {
                    assertEquals(JsonNull, element)
                    return JsonPrimitive(7)
                }
            }
        val opaque = RecordingCodec(serializer, Tick.serializer(), String.serializer(), Empty.serializer())
        val step = RecordedStep.Ignored<Int, Tick, String, Empty>(MachineInput.EntryResult(ActivationId(1), EntryOutput.Transition(7), MachineTime.Zero), IgnoreReason.StaleCommand)
        val saved = opaque.encodeStep(step)
        assertTrue(saved.contains("\"entryContext\":null"))
        assertEquals(step, opaque.decodeStep(saved, definition, version))
        assertFailsWith<IllegalArgumentException> { opaque.decodeStep(saved.replace(",\"entryContext\":null", ""), definition, version) }
    }

    @Test
    fun checkpointDurationFailureKeepsItsPublicMessageAndLocation() {
        val idle = StateId("Idle")
        val machine = stateMachine<Int, Tick, String, Empty>(definition, version, idle) { state(idle) {} }
        val checkpoint = ExecutorCheckpoint<Int, String>(machine.initialSnapshot(0), MachineTime.Zero)
        val saved = codec.encodeCheckpoint(checkpoint)
        assertEquals(checkpoint, codec.decodeCheckpoint(saved, definition, version))
        val corrupted = saved.replace("\"now\":\"PT0S\"", "\"now\":\"invalid\"")
        assertTrue(corrupted != saved)

        val error = assertFailsWith<IllegalArgumentException> { codec.decodeCheckpoint(corrupted, definition, version) }

        assertEquals("not an ISO-8601 duration: 'invalid' (at checkpoint)", error.message)
        assertNull(error.cause)
    }

    @Test
    fun aMissingStepActionKeepsItsPublicMessageAndLocation() {
        val saved = """{"input":{"type":"dispatch","now":"PT0S"},"ignored":"NoTransition"}"""

        val error = assertFailsWith<IllegalArgumentException> { codec.decodeStep(saved, definition, version) }

        assertEquals("missing action (at step: action)", error.message)
        assertNull(error.cause)
    }

    @Test
    fun malformedCheckpointJsonKeepsThePublicFormatErrorWithoutAnInternalCause() {
        val error = assertFailsWith<IllegalArgumentException> { codec.decodeCheckpoint("{", definition, version) }

        assertTrue(error.message.orEmpty().startsWith("not a checkpoint of format 7: "))
        assertNull(error.cause)
    }
}
