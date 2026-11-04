package actron.example.weather

import actron.decompose.retainedMachine
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.recording
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.citySearchHandler
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.currentWeatherHandler
import actron.example.weather.feature.weather.domain.WeatherRepository
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedMachineTest {
    @Test
    fun oneCallStartsRetainsAndClosesWorkWithItsKeeper() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            var started = 0
            var cancelled = 0

            suspend fun request(): Nothing {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
            val handler = currentWeatherHandler(repository = WeatherRepository { request() })

            fun create() =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    key = "weather",
                    machine = CurrentWeatherMachine.create(),
                    initial = CurrentWeatherState(demoCities.first()),
                    handler = handler,
                    coroutineContext = coroutineContext,
                    initialActions = listOf(CurrentWeatherAction.Refresh),
                )
            val store = create()
            testScheduler.runCurrent()
            assertTrue(store.state.value.isStarted)
            assertEquals(1, started)
            assertSame(store, create())
            testScheduler.runCurrent()
            assertEquals(1, started, "Host recreation must not repeat startup actions")
            assertEquals(0, cancelled)
            keeper.destroy()
            testScheduler.runCurrent()
            assertEquals(1, cancelled)
        }

    @Test
    fun explicitKeysKeepTwoInstancesIndependent() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val context = DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper)
            val handler = currentWeatherHandler(repository = WeatherRepository { awaitCancellation() })
            val first = context.retainedMachine("first", CurrentWeatherMachine.create(), CurrentWeatherState(demoCities[0]), handler, coroutineContext = coroutineContext)
            val second = context.retainedMachine("second", CurrentWeatherMachine.create(), CurrentWeatherState(demoCities[1]), handler, coroutineContext = coroutineContext)
            testScheduler.runCurrent()
            assertNotSame(first, second)
            assertEquals(demoCities[0], first.state.value.context.city)
            assertEquals(demoCities[1], second.state.value.context.city)
            keeper.destroy()
        }

    @Test
    fun recordingObservesStartupAndIsNotDuplicatedByHostRecreation() =
        runTest {
            val history = WeatherHistory()
            val keeper = InstanceKeeperDispatcher()

            fun create() =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    key = "search",
                    machine = CitySearchMachine.create(),
                    initial = CitySearchState(),
                    handler = citySearchHandler(LocalCitySearchRepository),
                    restoration = history.session.searchRestoration(),
                    recording = history.recording(),
                    coroutineContext = coroutineContext,
                )
            val store = create()
            testScheduler.runCurrent()
            assertTrue(store.state.value.isStarted)
            assertEquals(1, history.timeline.entries.value.size, "Recording must attach before startup")
            assertEquals(
                "city-search",
                history.timeline.entries.value
                    .single()
                    .machine.stableId,
            )
            assertSame(store, create())
            testScheduler.runCurrent()
            assertEquals(1, history.timeline.entries.value.size, "Retention must not register another recorder")
            keeper.destroy()
        }
}
