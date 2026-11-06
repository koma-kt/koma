package actron.example.weather.feature.cities.presentation

import actron.compose.collectAsUiState
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.statechart.machine.MachineSnapshot
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
    val state: StateFlow<MachineSnapshot<CitySearchState>>

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
    private val store: MachineStore<CitySearchState, CitySearchAction, SearchCities, NoEvent>,
    private val settingsAction: @Composable () -> Unit,
) : CitySearchComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    override val state = store.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CitySearchIntent) {
        if (!state.value.isStarted) return
        when (event) {
            // Enqueue an intent; the machine is the only writer of the search context.
            is CitySearchIntent.QueryChanged -> store.dispatch(CitySearchAction.ChangeQuery(event.query))

            CitySearchIntent.Retry -> store.dispatch(CitySearchAction.ChangeQuery(state.value.context.query))

            is CitySearchIntent.SelectCity -> scope.launch { endpoint.news.emit(CitySearchComponent.News.CitySelected(event.city)) }
        }
    }

    @Composable
    override fun Render() {
        val snapshot by state.collectAsUiState()
        val controls = if (snapshot.isStarted) CitySearchControls.Interactive else CitySearchControls.ReadOnly
        CitySearchScreen(snapshot.context, controls, onIntent = ::onUiEvent, settingsAction = settingsAction)
    }
}
