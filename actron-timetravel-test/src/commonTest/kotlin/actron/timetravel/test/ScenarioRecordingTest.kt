package actron.timetravel.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.typedStateMachine
import actron.statechart.test.scenarios
import actron.timetravel.HistoryExportSettings
import actron.timetravel.HistoryRecordingCodec
import actron.timetravel.HistoryTimeline
import actron.timetravel.historyDefinition
import actron.timetravel.historyFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ScenarioRecordingTest {
    sealed interface Phase {
        data object Idle : Phase

        data object Ready : Phase
    }

    sealed interface Act : Action {
        data object Go : Act

        data object Unknown : Act
    }

    sealed interface Ev : Event

    @Test
    fun recordingKeepsBeforeInputAfterCoverageAndVirtualTimeIncludingIgnoredStep() {
        val machine =
            typedStateMachine<Phase, Act, Unit, Ev>(
                DefinitionId("recorded"),
                DefinitionVersion("1"),
                Phase.Idle,
            ) {
                state<Phase.Idle> { on<Act.Go, Phase.Ready> { _, _ -> Phase.Ready } }
                state<Phase.Ready> {}
            }
        val model =
            machine.scenarios(Phase.Idle) {
                actions(Act.Go)
                inputs { snapshot, now, _ ->
                    if (snapshot.context == Phase.Ready) listOf(MachineInput.Dispatch(Act.Unknown, MachineTime(now.sinceStart + 5.milliseconds))) else emptyList()
                }
                goal("ignored-after-ready") { steps ->
                    steps.last().before.context == Phase.Ready && steps.last().input is MachineInput.Dispatch &&
                        steps
                            .last()
                            .explained.decision.outcome is DecisionOutcome.Ignored
                }
            }
        val generated = model.generate(maxDepth = 2)
        generated.assertSuccess()
        val trace = model.record(generated.witnesses.getValue("ignored-after-ready"))
        trace.assertSuccess()
        val started = Instant.parse("2026-10-20T00:00:00Z")
        val definition = machine.chart.historyDefinition(machine.id.value, machine.version.value)

        fun describe(phase: Phase) = buildJsonObject { put("phase", phase.toString()) }
        val recording =
            trace.recording(
                "test/1",
                listOf(definition),
                ::describe,
                snapshot = { step, coverage ->
                    describe(step.after.context).let { fields ->
                        JsonObject(fields + ("hits" to JsonPrimitive(coverage.hits.values.sum())))
                    }
                },
                settings = HistoryExportSettings("Tests"),
                startedAt = started,
            )
        val codec = HistoryRecordingCodec("test/1", JsonObject.serializer(), definitions = listOf(definition))
        val restored = codec.decode(codec.encode(recording))
        assertEquals(3, restored.entries.size)
        for ((step, entry) in trace.steps.zip(restored.entries)) {
            assertEquals(started + step.input.now.sinceStart, Instant.parse(entry.at))
            assertEquals(describe(step.after.context).getValue("phase"), entry.snapshot.getValue("phase"))
            assertEquals(historyFields(describe(step.before.context)), entry.details.before.filter { it.path == "phase" })
            assertEquals(historyFields(describe(step.after.context)), entry.details.after.filter { it.path == "phase" })
        }
        assertEquals(
            JsonPrimitive(1L),
            restored.entries
                .last()
                .snapshot
                .getValue("hits"),
        )
        assertEquals(
            restored.entries
                .last()
                .details.before,
            restored.entries
                .last()
                .details.after,
        )
        assertTrue(
            restored.entries
                .last()
                .details.outcome
                .contains("Ignored"),
        )
        assertEquals(
            "Action",
            restored.entries
                .last()
                .details.source,
        )
        assertEquals("Tests", restored.metadata.projectName)
        assertEquals(
            restored.entries.size,
            HistoryTimeline
                .fromRecording(restored)
                .entries.value.size,
        )
        assertFailsWith<IllegalArgumentException> {
            trace.recording("test/1", emptyList(), ::describe, { step, _ -> describe(step.after.context) })
        }
    }
}
