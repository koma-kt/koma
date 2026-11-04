package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Version 7 records state-owned onEnter work and its replayable results. Other versions are rejected. */
const val RECORDING_FORMAT_VERSION: Int = 7

/**
 * What [RecordingCodec.decode] found.
 */
sealed interface DecodedRecording<C : Any, A : Action, CMD : Any, E : Event> {
    /** The recording, exactly as encoded. */
    data class Decoded<C : Any, A : Action, CMD : Any, E : Event>(
        val recording: Recording<C, A, CMD, E>,
    ) : DecodedRecording<C, A, CMD, E>

    /** The format version is not supported by this codec. */
    data class Unsupported<C : Any, A : Action, CMD : Any, E : Event>(
        val formatVersion: Int,
        val reason: String,
    ) : DecodedRecording<C, A, CMD, E>

    /** The text is not a recording of this format, or a payload could not be decoded; [at] says where. */
    data class Invalid<C : Any, A : Action, CMD : Any, E : Event>(
        val reason: String,
        val location: RecordingLocation = RecordingLocation.Document,
    ) : DecodedRecording<C, A, CMD, E> {
        constructor(reason: String, at: String) : this(reason, RecordingLocation.Part(at))
    }
}

/** Scope of a decoder failure: the document itself or an actual named part of it. */
sealed interface RecordingLocation {
    fun withPart(accept: (String) -> Unit)

    data object Document : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = Unit
    }

    data class Part(
        val path: String,
    ) : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = accept(path)
    }
}

/**
 * Encodes a [Recording] as JSON and decodes it back, with the application's serializers for its
 * own types. The structure (the start checkpoint, snapshots, inputs, decisions, ids, timers,
 * failures) is the codec's and versioned by [RECORDING_FORMAT_VERSION]; the context, actions,
 * commands and events are whatever the serializers write. The output is canonical: fields in a
 * fixed order, maps sorted by key, commands sorted by id, no defaults, no nulls, durations in
 * ISO-8601, so equal recordings encode to equal text and the text can be a golden fixture.
 *
 * A decoded recording equals the encoded one when payload serializers round-trip their values.
 * Other format versions are unsupported; malformed data is invalid.
 *
 * @param context Serializer of the machine's context
 * @param action Serializer of the actions (dispatched and returned by commands)
 * @param command Serializer of the commands
 * @param event Serializer of the events
 */
class RecordingCodec<C : Any, A : Action, CMD : Any, E : Event>(
    private val context: KSerializer<C>,
    private val action: KSerializer<A>,
    private val command: KSerializer<CMD>,
    private val event: KSerializer<E>,
) {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
            prettyPrint = false
            ignoreUnknownKeys = false
        }

    private val writer = RecordingWireEncoder(json, context, action, command, event)
    private val reader = RecordingWireReader(json, context, action, command, event)

    /**
     * The canonical JSON of [recording].
     */
    fun encode(recording: Recording<C, A, CMD, E>): String = json.encodeToString(RecordingWire.serializer(), with(writer) { recording.toWire() })

    /**
     * The recording in [text], if it uses the current format.
     */
    fun decode(text: String): DecodedRecording<C, A, CMD, E> {
        val element: JsonObject =
            try {
                json.parseToJsonElement(text) as? JsonObject ?: return DecodedRecording.Invalid("not a JSON object")
            } catch (e: SerializationException) {
                return DecodedRecording.Invalid("not JSON: ${e.message}")
            }
        val formatVersion =
            (element["formatVersion"] as? JsonPrimitive)?.intOrNull
                ?: return DecodedRecording.Invalid("no formatVersion")
        if (formatVersion != RECORDING_FORMAT_VERSION) {
            return DecodedRecording.Unsupported(formatVersion = formatVersion, reason = "Only recording format $RECORDING_FORMAT_VERSION is supported")
        }
        val wire =
            try {
                json.decodeFromJsonElement(RecordingWire.serializer(), element)
            } catch (e: SerializationException) {
                return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
            }
        return try {
            DecodedRecording.Decoded(reader.readRecording(wire))
        } catch (e: DecodeFailure) {
            DecodedRecording.Invalid(e.message ?: "invalid", e.at)
        } catch (e: IllegalArgumentException) {
            DecodedRecording.Invalid(e.message ?: "invalid")
        }
    }

    // --- parts, for the segments of a recording file ---

    /** The JSON of [checkpoint] alone. */
    fun encodeCheckpoint(checkpoint: ExecutorCheckpoint<C, CMD>): String = json.encodeToString(CheckpointWire.serializer(), with(writer) { checkpoint.toWire() })

    /**
     * The checkpoint [encodeCheckpoint] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeCheckpoint(
        text: String,
        definition: DefinitionId,
        version: DefinitionVersion,
    ): ExecutorCheckpoint<C, CMD> = part("checkpoint") { with(reader) { json.decodeFromString(CheckpointWire.serializer(), text).toCheckpoint(definition, version, "checkpoint") } }

    /** The JSON of [step] alone. */
    fun encodeStep(step: RecordedStep<C, A, CMD, E>): String = json.encodeToString(StepWire.serializer(), with(writer) { step.toWire() })

    /**
     * The step [encodeStep] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeStep(
        text: String,
        definition: DefinitionId,
        version: DefinitionVersion,
    ): RecordedStep<C, A, CMD, E> = part("step") { with(reader) { json.decodeFromString(StepWire.serializer(), text).toStep(definition, version, "step") } }

    private inline fun <T : Any> part(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a $what of format $RECORDING_FORMAT_VERSION: ${e.message}")
        } catch (e: DecodeFailure) {
            throw IllegalArgumentException("${e.message} (at ${e.at})")
        }
}

internal class DecodeFailure : RuntimeException {
    val at: String

    constructor(message: String, at: String) : super(message) {
        this.at = at
    }

    constructor(message: String, at: String, cause: Throwable) : super(message, cause) {
        this.at = at
    }
}
