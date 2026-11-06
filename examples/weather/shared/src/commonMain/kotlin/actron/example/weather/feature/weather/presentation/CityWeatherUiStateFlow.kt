package actron.example.weather.feature.weather.presentation

import actron.statechart.machine.MachineSnapshot
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reads current sources directly; each UI collector owns both subscriptions and their cancellation. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class CityWeatherUiStateFlow(
    private val weather: StateFlow<WeatherContext>,
    private val screen: StateFlow<MachineSnapshot<WeatherScreenState>>,
) : StateFlow<CityWeatherUiState> {
    override val value: CityWeatherUiState get() = weather.value.toUiState(enabled = screen.value.isStarted)

    override val replayCache: List<CityWeatherUiState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<CityWeatherUiState>): Nothing {
        combine(weather, screen) { context, parent -> context.toUiState(enabled = parent.isStarted) }
            .distinctUntilChanged()
            .collect(collector)
        error("StateFlow collection must not complete")
    }
}
