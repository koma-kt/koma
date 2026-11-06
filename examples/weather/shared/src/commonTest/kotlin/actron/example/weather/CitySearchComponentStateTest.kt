@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.core.component.ParentBridge
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchControls
import actron.example.weather.feature.cities.presentation.CitySearchIntent
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.CitySearchUiState
import actron.example.weather.feature.cities.presentation.DefaultCitySearchComponent
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CitySearchComponentStateTest {
    @Test
    fun componentExposesLiveUiProjectionWithoutStartingStoreOnRead() =
        runTest {
            val lifecycle = LifecycleRegistry()
            val store =
                MachineStore(
                    machine = CitySearchMachine.create { error("Debounce must prevent requests") },
                    context = CitySearchState.Idle(),
                    scope = backgroundScope,
                    clock = MachineClock.monotonic(testScheduler.timeSource),
                    coroutineContext = coroutineContext,
                )
            val component: CitySearchComponent =
                DefaultCitySearchComponent(
                    componentContext = DefaultComponentContext(lifecycle),
                    endpoint = ParentBridge<Nothing, CitySearchComponent.News>().endpoint,
                    coroutineContext = coroutineContext,
                    store = store,
                    settingsAction = {},
                )
            try {
                val state: StateFlow<CitySearchUiState> = component.state
                assertEquals(CitySearchControls.ReadOnly, state.value.controls)
                assertEquals(listOf(state.value), state.replayCache)
                runCurrent()
                assertFalse(store.state.value.isStarted, "Reading the projection must preserve lazy startup")
                component.onUiEvent(CitySearchIntent.QueryChanged("ignored before startup"))
                store.start()
                runCurrent()
                assertEquals("", state.value.query)
                assertEquals(CitySearchControls.Interactive, state.value.controls)
                // No UI collector is needed to keep value current.
                component.onUiEvent(CitySearchIntent.QueryChanged("Paris"))
                runCurrent()
                assertEquals("Paris", state.value.query)
                val values = mutableListOf<CitySearchUiState>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        state.collect { values.add(it) }
                    }
                assertEquals(listOf(state.value), values)
                component.onUiEvent(CitySearchIntent.QueryChanged("Tokyo"))
                runCurrent()
                assertEquals("Tokyo", values.last().query)
                assertEquals(state.value, values.last())
                assertEquals(listOf(state.value), state.replayCache)
                val emissionCount = values.size
                component.onUiEvent(CitySearchIntent.QueryChanged("Tokyo"))
                runCurrent()
                assertEquals(emissionCount, values.size, "Internal revisions must not emit equal UI models")
                collector.cancel()
                runCurrent()
                component.onUiEvent(CitySearchIntent.QueryChanged("Lisbon"))
                runCurrent()
                assertEquals("Lisbon", state.value.query)
                assertEquals("Tokyo", values.last().query)
            } finally {
                lifecycle.destroy()
                store.closeAndJoin()
            }
        }
}
