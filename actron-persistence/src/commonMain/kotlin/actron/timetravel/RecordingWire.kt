package actron.timetravel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// The wire model: field order is the canonical order of the JSON.

@Serializable
internal class RecordingWire(
    val formatVersion: Int,
    val definition: String,
    val version: String,
    val start: CheckpointWire,
    val steps: List<StepWire>,
)

@Serializable
internal class CheckpointWire(
    val snapshot: SnapshotWire,
    val now: String,
    val running: List<CommandRegistrationWire> = emptyList(),
    val queued: Map<String, List<CommandRegistrationWire>> = emptyMap(),
    val ending: List<CommandRegistrationWire> = emptyList(),
    val effects: List<PendingEffectWire> = emptyList(),
)

@Serializable
internal class PendingEffectWire(
    val id: Long,
    val event: JsonElement,
    val policy: EffectPolicyWire,
    val attempts: Int,
    val handling: Boolean,
)

@Serializable
internal class EffectPolicyWire(
    val kind: String,
    @Serializable(with = StringWireNode::class) val key: JsonElement = JsonNull,
    @Serializable(with = IntWireNode::class) val maxAttempts: JsonElement = JsonNull,
)

@Serializable
internal class SnapshotWire(
    val revision: Long,
    val active: List<String>,
    val history: Map<String, List<String>>,
    val context: JsonElement,
    val activations: Map<String, Long>,
    val commands: Map<String, CommandRecordWire>,
    val timers: Map<String, TimerWire>,
    val counters: CountersWire,
    val pendingEntries: Map<String, EntryRecordWire>,
)

@Serializable
internal class CommandRecordWire(
    val scope: Long,
    @Serializable(with = StringWireNode::class) val lane: JsonElement = JsonNull,
)

@Serializable
internal class TimerWire(
    val transition: Int,
    val activation: Long,
    val deadline: String,
)

@Serializable
internal class CountersWire(
    val activations: Long,
    val commands: Long,
    val timers: Long,
    val effects: Long,
)

@Serializable
internal class StepWire(
    val input: JsonObject,
    @Serializable(with = DecisionWireNode::class) val committed: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val ignored: JsonElement = JsonNull,
    @Serializable(with = FailureWireNode::class) val failed: JsonElement = JsonNull,
)

@Serializable
internal class InputWire(
    val type: String,
    val now: String,
    val action: JsonElement = JsonNull,
    val activation: JsonElement = JsonNull,
    val entryKind: JsonElement = JsonNull,
    val entryContext: JsonElement = JsonNull,
    val entryEvent: JsonElement = JsonNull,
    @Serializable(with = LongWireNode::class) val command: JsonElement = JsonNull,
    @Serializable(with = LongWireNode::class) val timer: JsonElement = JsonNull,
    @Serializable(with = FailureWireNode::class) val failure: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val reason: JsonElement = JsonNull,
    @Serializable(with = MessageWireNode::class) val message: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val source: JsonElement = JsonNull,
)

@Serializable
internal class MessageWire(
    val from: String,
    val effect: Long,
)

@Serializable
internal class DecisionWire(
    val snapshot: SnapshotWire,
    val transitions: List<Int>,
    val exited: List<ActivationWire>,
    val entered: List<ActivationWire>,
    val commands: List<CommandRegistrationWire>,
    val cancelledScopes: List<Long>,
    val timersScheduled: List<TimerScheduleWire>,
    val timersCancelled: List<Long>,
    val effects: List<EffectWire>,
)

@Serializable
internal class ActivationWire(
    val node: String,
    val id: Long,
)

@Serializable
internal class CommandRegistrationWire(
    val id: Long,
    val command: JsonElement,
    val scope: Long,
    @Serializable(with = StringWireNode::class) val lane: JsonElement = JsonNull,
    @Serializable(with = PolicyWireNode::class) val policy: JsonElement = JsonNull,
)

@Serializable
internal class PolicyWire(
    val kind: String,
    @Serializable(with = IntWireNode::class) val limit: JsonElement = JsonNull,
)

@Serializable
internal class TimerScheduleWire(
    val id: Long,
    val transition: Int,
    val activation: Long,
    val deadline: String,
)

@Serializable
internal class EffectWire(
    val id: Long,
    val event: JsonElement,
)

@Serializable
internal class FailureWire(
    @Serializable(with = StringWireNode::class) val type: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val message: JsonElement = JsonNull,
    @Serializable(with = FailureWireNode::class) val cause: JsonElement = JsonNull,
    val suppressed: List<FailureWire> = emptyList(),
)

// These serializers represent the current JSON field schema without Kotlin nullable values.
// The concrete JsonNull node denotes a protocol token; domain contracts are normalized above.
private abstract class WireNode<T : Any>(
    private val schema: () -> KSerializer<T>,
) : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor get() = JsonElement.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: JsonElement,
    ) {
        (encoder as JsonEncoder).encodeJsonElement(value)
    }

    override fun deserialize(decoder: Decoder): JsonElement {
        val input = decoder as JsonDecoder
        val node = input.decodeJsonElement()
        if (node != JsonNull) input.json.decodeFromJsonElement(schema(), node)
        return node
    }
}

private object StringWireNode : WireNode<String>({ String.serializer() })

private object IntWireNode : WireNode<Int>({ Int.serializer() })

private object LongWireNode : WireNode<Long>({ Long.serializer() })

private object MessageWireNode : WireNode<MessageWire>({ MessageWire.serializer() })

private object PolicyWireNode : WireNode<PolicyWire>({ PolicyWire.serializer() })

private object DecisionWireNode : WireNode<DecisionWire>({ DecisionWire.serializer() })

private object FailureWireNode : WireNode<FailureWire>({ FailureWire.serializer() })

@Serializable
internal class EntryRecordWire(
    val activation: Long,
    val command: Long,
)
