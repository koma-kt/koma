package actron.example.weather.feature.cities.presentation

import actron.statechart.machine.MachineSnapshot
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A live projection: reads do not start the Store, and collectors own their source subscription. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class CitySearchUiStateFlow(
    private val source: StateFlow<MachineSnapshot<CitySearchState>>,
) : StateFlow<CitySearchUiState> {
    override val value: CitySearchUiState get() = source.value.toUiState()

    override val replayCache: List<CitySearchUiState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<CitySearchUiState>): Nothing {
        source.map { it.toUiState() }.distinctUntilChanged().collect(collector)
        error("StateFlow collection must not complete")
    }
}
