package actron.example.weather.feature.cities.presentation

import actron.compose.collectAsUiState
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.statechart.machine.MachineStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

interface CitySearchComponent : BaseComponent {
    val state: StateFlow<CitySearchUiState>

    fun onUiEvent(event: CitySearchIntent)

    sealed interface News {
        data class CitySelected(
            val city: City,
        ) : News
    }

    interface Factory {
        fun create(
            componentContext: ComponentContext,
            endpoint: BridgeEndpoint<Nothing, News>,
        ): CitySearchComponent
    }
}

class DefaultCitySearchComponent(
    componentContext: ComponentContext,
    private val endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    coroutineContext: CoroutineContext,
    private val store: MachineStore<CitySearchState, CitySearchAction, Unit, NoEvent>,
    private val settingsAction: @Composable () -> Unit,
) : CitySearchComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    override val state: StateFlow<CitySearchUiState> = CitySearchUiStateFlow(store.state)

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CitySearchIntent) {
        val snapshot = store.state.value
        if (!snapshot.isStarted) return
        when (val command = event.toCommand(snapshot.context)) {
            is CitySearchUiCommand.Dispatch -> store.dispatch(command.action)
            is CitySearchUiCommand.Publish -> scope.launch { endpoint.news.emit(command.news) }
        }
    }

    @Composable
    override fun Render() {
        val uiState by state.collectAsUiState()
        CitySearchScreen(uiState = uiState, onIntent = ::onUiEvent, settingsAction = settingsAction)
    }
}
