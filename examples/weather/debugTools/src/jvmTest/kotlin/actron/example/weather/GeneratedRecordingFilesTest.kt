package actron.example.weather

import actron.example.weather.app.debug.WeatherRecording
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/** Import these output files in Weather's inspector to inspect automatically discovered paths. */
class GeneratedRecordingFilesTest {
    @Test fun writesImportableSearchAndIndependentWeatherScenarios() =
        runTest {
            val directory = File("build/generated-scenarios").apply { mkdirs() }
            val search = GeneratedWeatherScenarios.search(scope = this)
            val weather = GeneratedWeatherScenarios.weather(scope = this)
            val recordings =
                mapOf(
                    "late-search-results" to searchRecording(trace = search),
                    "current-weather-success" to weatherRecording(trace = weather),
                )
            for ((name, recording) in recordings) {
                val file = File(directory, "$name.actron.json")
                val bytes = WeatherRecording.codec.encode(recording)
                WeatherRecording.import(bytes)
                file.writeBytes(bytes)
                println("Generated scenario recording: ${file.absolutePath}")
            }
        }
}
