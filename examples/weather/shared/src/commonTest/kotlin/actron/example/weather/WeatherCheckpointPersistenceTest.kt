@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.decompose.retainedMachine
import actron.example.weather.app.session.WeatherSavedState
import actron.example.weather.app.session.WeatherSavedStateCodec
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.*
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class WeatherCheckpointPersistenceTest {
    private fun clock(scheduler: TestCoroutineScheduler) =
        object : MachineClock {
            override fun now() = MachineTime(scheduler.currentTime.milliseconds)

            override suspend fun delayUntil(deadline: MachineTime) {
                val remaining = deadline - now()
                if (remaining.isPositive()) delay(remaining)
            }
        }

    @Test
    fun restoredDebounceRestartsFullDelayAndCompletedSearchDoesNotReload() =
        runTest {
            val requests = mutableListOf<String>()
            val options = listOf(CityOption(demoCities[0], "saved region"))
            val repo =
                CitySearchRepository { query ->
                    requests += query
                    options
                }
            var history = WeatherSession(clockFactory = { clock(testScheduler) })

            var keeper = InstanceKeeperDispatcher()

            fun destroy() {
                keeper.destroy()
                keeper = InstanceKeeperDispatcher()
            }

            fun instance() =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    key = "city-search",
                    machine = CitySearchMachine.create(),
                    initial = CitySearchState(),
                    handler = citySearchHandler(repo),
                    coroutineContext = coroutineContext,
                    restoration = history.searchRestoration(),
                )
            var machine = instance()
            runCurrent()
            machine.dispatch(CitySearchAction.ChangeQuery("Paris"))
            runCurrent()
            advanceTimeBy(100)
            val saved = Json.decodeFromString(WeatherSavedState.serializer(), Json.encodeToString(WeatherSavedState.serializer(), WeatherSavedStateCodec.encode(history)))
            destroy()
            runCurrent()
            advanceTimeBy(60_000)
            history = WeatherSavedStateCodec.decodeSession(saved) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals(RequestState.Debouncing, machine.state.value.context.phase)
            advanceTimeBy(349)
            runCurrent()
            assertTrue(requests.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("Paris"), requests)
            assertEquals(RequestState.Success(options), machine.state.value.context.phase)
            val completed = WeatherSavedStateCodec.encode(history)
            destroy()
            runCurrent()
            history = WeatherSavedStateCodec.decodeSession(completed) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals("Paris", machine.state.value.context.query)
            assertEquals(RequestState.Success(options), machine.state.value.context.phase)
            assertEquals(listOf("Paris"), requests)
            destroy()
        }

    @Test
    fun runningSearchRestartsOnceAndAcceptsNewInput() =
        runTest {
            var calls = 0
            val repo =
                CitySearchRepository {
                    calls++
                    awaitCancellation()
                }
            var history = WeatherSession(clockFactory = { clock(testScheduler) })

            var keeper = InstanceKeeperDispatcher()

            fun destroy() {
                keeper.destroy()
                keeper = InstanceKeeperDispatcher()
            }

            fun instance() =
                DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper).retainedMachine(
                    key = "city-search",
                    machine = CitySearchMachine.create(),
                    initial = CitySearchState(),
                    handler = citySearchHandler(repo),
                    coroutineContext = coroutineContext,
                    restoration = history.searchRestoration(),
                )
            var machine = instance()
            runCurrent()
            machine.dispatch(CitySearchAction.ChangeQuery("Paris"))
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(1, calls)
            val saved = WeatherSavedStateCodec.encode(history)
            destroy()
            runCurrent()
            history = WeatherSavedStateCodec.decodeSession(saved) { clock(testScheduler) }
            machine = instance()
            runCurrent()
            assertEquals(2, calls)
            assertEquals(RequestState.Loading, machine.state.value.context.phase)
            machine.dispatch(CitySearchAction.ChangeQuery("Tokyo"))
            runCurrent()
            assertEquals("Tokyo", machine.state.value.context.query)
            assertEquals(RequestState.Debouncing, machine.state.value.context.phase)
            destroy()
        }

    @Test
    fun rejectsUnsupportedOrCorruptCheckpoint() {
        val saved = WeatherSavedStateCodec.encode(WeatherSession())
        assertFailsWith<IllegalArgumentException> { WeatherSavedStateCodec.decodeSession(saved.copy(format = 99)) }
        assertFailsWith<IllegalArgumentException> { WeatherSavedStateCodec.decodeSession(saved.copy(checkpoints = "broken")) }
    }
}
