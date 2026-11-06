@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.app.debug.HistoryFileResult
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.WeatherRecording
import actron.example.weather.app.debug.WeatherRecordingViewer
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.domain.*
import actron.timetravel.HistoryTimeline
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WeatherRecordingIsolationTest {
    @Test fun browsingImportedPendingStatesCannotStartRequestsOrNavigateLiveComponents() =
        runTest {
            var searchCalls = 0
            var weatherCalls = 0
            val lifecycle = LifecycleRegistry()
            val history = WeatherHistory()
            val root =
                DebugWeatherRootComponent(
                    DefaultComponentContext(lifecycle),
                    WeatherRepository {
                        weatherCalls++
                        awaitCancellation()
                    },
                    CitySearchRepository {
                        searchCalls++
                        awaitCancellation()
                    },
                    HourlyForecastRepository {
                        weatherCalls++
                        awaitCancellation()
                    },
                    uiContext = coroutineContext,
                    machineContext = coroutineContext,
                    history = history,
                )
            lifecycle.resume()
            try {
                testScheduler.runCurrent()
                val component =
                    root
                        .getChildStack()
                        .value.active.instance as CitySearchComponent
                component.onUiEvent(CitySearchIntent.QueryChanged("Live account"))
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(350)
                testScheduler.runCurrent()
                assertEquals(1, searchCalls)
                val frame = history.session.frame
                val viewer = WeatherRecordingViewer()
                viewer.open(RecordingTestFiles(HistoryFileResult.Opened("another-account.json", WeatherRecording.export(recordingHistory()))))
                val imported = viewer.imported.single()
                HistoryTimeline
                    .fromRecording(imported.recording)
                    .entries.value
                    .forEach(imported.panel::select)
                testScheduler.runCurrent()
                assertEquals(1, searchCalls)
                assertEquals(0, weatherCalls)
                assertSame(
                    component,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
                assertSame(frame, history.session.frame)
                viewer.close()
                assertSame(
                    component,
                    root
                        .getChildStack()
                        .value.active.instance,
                )
            } finally {
                lifecycle.destroy()
                testScheduler.runCurrent()
            }
        }
}
