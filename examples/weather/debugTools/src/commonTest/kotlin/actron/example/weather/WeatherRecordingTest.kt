package actron.example.weather

import actron.example.weather.app.debug.HistoryFileResult
import actron.example.weather.app.debug.HistoryFiles
import actron.example.weather.app.debug.WeatherDisplay
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.WeatherHistoryFrame
import actron.example.weather.app.debug.WeatherRecording
import actron.example.weather.app.debug.WeatherRecordingFrame
import actron.example.weather.app.debug.WeatherRecordingViewer
import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.app.session.freshWeather
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.*
import actron.timetravel.*
import kotlin.test.*

internal fun recordingHistory(): WeatherHistory {
    val history = WeatherHistory()
    val search = CitySearchMachine.create()
    val searchState = search.initialSnapshot(CitySearchState("Москва", RequestState.Loading))
    val searching = WeatherFrame(search = ExecutorCheckpoint.initial(searchState))
    val details = HistoryDetails("ChangeQuery(Москва)", "Action", "Handled", emptyList(), listOf(StateField("query", "Москва")))
    history.timeline.append(history.timeline.machine("City Search", "city-search"), 0, details, WeatherHistoryFrame(searching))
    val city = City("Москва", 55.75, 37.62)
    val weather =
        freshWeather(city = city).let { cut ->
            cut.copy(
                current =
                    cut.current.copy(
                        snapshot =
                            cut.current.snapshot.copy(
                                context =
                                    cut.current.snapshot.context
                                        .copy(phase = RequestState.Loading),
                            ),
                    ),
            )
        }
    history.timeline.append(history.timeline.machine("City Weather", "city-weather"), 0, details.copy(input = "Refresh"), WeatherHistoryFrame(searching.copy(route = WeatherRoute.Weather(checkpoint = weather))))
    return history
}

internal class RecordingTestFiles(
    var next: HistoryFileResult,
) : HistoryFiles {
    var saved = byteArrayOf()

    override fun open(result: (HistoryFileResult) -> Unit) = result(next)

    override fun save(
        name: String,
        bytes: ByteArray,
        result: (HistoryFileResult) -> Unit,
    ) {
        saved = bytes
        result(next)
    }
}

class WeatherRecordingTest {
    @Test fun pendingRequestsBecomeDisplayDataOnlyAndLiveSessionIsUntouched() {
        val history = recordingHistory()
        val previous = history.session.frame
        val events = history.timeline.entries.value
        val bytes = WeatherRecording.export(history)
        val viewer = WeatherRecordingViewer()
        viewer.open(RecordingTestFiles(HistoryFileResult.Opened("запись.json", bytes)))
        val imported = viewer.imported.single()
        val entries = HistoryTimeline.fromRecording(imported.recording).entries.value
        assertEquals("Москва", (entries.first().snapshot.screen as WeatherDisplay.Search).state.query)
        assertEquals(RequestState.Loading, (entries.last().snapshot.screen as WeatherDisplay.Weather).state.current)
        entries.forEach(imported.panel::select)
        assertSame(previous, history.session.frame)
        assertEquals(events, history.timeline.entries.value)
        assertEquals(0, history.session.branch)
        assertFalse(bytes.decodeToString().contains("checkpoints"))
        viewer.close()
        assertTrue(viewer.imported.isEmpty())
        assertSame(previous, history.session.frame)
    }

    @Test fun failedOrCancelledOpenKeepsExistingRecordingAndSaveDoesNotChangeSession() {
        val history = recordingHistory()
        val viewer = WeatherRecordingViewer()
        val files = RecordingTestFiles(HistoryFileResult.Opened("good.json", WeatherRecording.export(history)))
        viewer.open(files)
        val selected = viewer.imported.single()
        files.next = HistoryFileResult.Opened("broken.json", "{}".encodeToByteArray())
        viewer.open(files)
        assertSame(selected, viewer.imported.single())
        assertTrue(viewer.message.isNotBlank())
        files.next = HistoryFileResult.Cancelled
        viewer.open(files)
        assertSame(selected, viewer.imported.single())
        viewer.save(files, history)
        assertEquals(2, WeatherRecording.import(files.saved).entries.size)
        assertFalse(viewer.busy)
        files.next = HistoryFileResult.Failed("Read failed")
        viewer.open(files)
        assertEquals("Read failed", viewer.message)
    }

    @Test fun incompatibleMachineVersionAndGraphAreRejectedWithoutReplacingSelection() {
        val history = recordingHistory()
        val recording = WeatherRecording.import(WeatherRecording.export(history))
        val permissive = HistoryRecordingCodec(WeatherRecording.codec.schema, WeatherRecordingFrame.serializer(), definitions = recording.definitions.map { it.copy(version = "old") })
        val old = permissive.encode(recording.copy(definitions = permissive.definitions))
        assertFailsWith<IllegalArgumentException> { WeatherRecording.import(old) }
        val wrong = recording.copy(entries = recording.entries.map { it.copy(snapshot = it.snapshot.copy(active = listOf("Unknown"))) })
        assertFailsWith<IllegalArgumentException> { WeatherRecording.import(WeatherRecording.codec.encode(wrong)) }
    }
}
