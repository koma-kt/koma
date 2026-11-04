package actron.example.weather

import actron.example.weather.app.debug.WeatherDisplay
import actron.example.weather.app.debug.WeatherRecording
import actron.example.weather.app.debug.WeatherRecordingFrame
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.toUiState
import actron.statechart.machine.*
import actron.statechart.test.*
import actron.timetravel.HistoryExportSettings
import actron.timetravel.test.recording
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class GeneratedWeatherScenariosTest {
    @Test fun executedSearchRecordsLateWorkerAndLateActionAndReplaysWithoutIo() =
        runTest {
            val trace = GeneratedWeatherScenarios.search(scope = this)
            trace.assertSuccess()
            val machine =
                actron.example.weather.feature.cities.presentation.CitySearchMachine
                    .create()
            var snapshot = trace.initial
            for (step in trace.steps) {
                val replayed = machine.decide(snapshot = snapshot, input = step.input)
                assertEquals(expected = step.after, actual = replayed.snapshot)
                snapshot = replayed.snapshot
            }
            assertTrue(actual = trace.steps.any { it.explained.decision.outcome is DecisionOutcome.Ignored })
            val imported = WeatherRecording.import(bytes = WeatherRecording.codec.encode(recording = searchRecording(trace = trace)))
            val screen =
                assertIs<WeatherDisplay.Search>(
                    value =
                        imported.entries
                            .last()
                            .snapshot.screen,
                )
            assertEquals(expected = "Казань", actual = screen.state.query)
            assertEquals(
                expected = "Казань",
                actual =
                    assertIs<RequestState.Success<List<CityOption>>>(value = screen.state.phase)
                        .value
                        .single()
                        .city.name,
            )
        }

    @Test fun independentCurrentWeatherRecordingRoundTrips() =
        runTest {
            val trace = GeneratedWeatherScenarios.weather(scope = this)
            trace.assertSuccess()
            val imported = WeatherRecording.import(bytes = WeatherRecording.codec.encode(recording = weatherRecording(trace = trace)))
            assertTrue(actual = imported.entries.isNotEmpty())
        }
}

fun <CMD : Any, E : actron.core.Event> searchRecording(trace: ScenarioTrace<CitySearchState, CitySearchAction, CMD, E>) =
    // Optional library bridge; Weather supplies its codec contract and display-only screen projection.
    trace.recording(
        WeatherRecording.codec.schema,
        WeatherRecording.definitions,
        describe = { Json.encodeToJsonElement(CitySearchState.serializer(), it) },
        snapshot = { step, coverage ->
            WeatherRecordingFrame(
                WeatherDisplay.Search(step.after.context),
                step.after.configuration.active
                    .map { it.value },
                coverage.hits.mapKeys { it.key.index },
            )
        },
        settings = HistoryExportSettings("Weather generated tests"),
        machineName = "City Search",
    )

fun <A : actron.core.Action, CMD : Any, E : actron.core.Event> weatherRecording(trace: ScenarioTrace<CurrentWeatherState, A, CMD, E>) =
    trace.recording(
        WeatherRecording.codec.schema,
        WeatherRecording.definitions,
        describe = { Json.encodeToJsonElement(CurrentWeatherState.serializer(), it) },
        snapshot = { step, coverage ->
            WeatherRecordingFrame(
                WeatherDisplay.Weather(WeatherContext(city = step.after.context.city, current = step.after.context.phase)),
                step.after.configuration.active
                    .map { it.value },
                coverage.hits.mapKeys { it.key.index },
            )
        },
        settings = HistoryExportSettings("Weather generated tests"),
        machineName = "Current Weather",
    )
