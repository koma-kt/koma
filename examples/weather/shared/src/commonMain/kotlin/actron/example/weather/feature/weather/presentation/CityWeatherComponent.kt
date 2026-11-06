package actron.example.weather.feature.weather.presentation

import actron.compose.collectAsUiState
import actron.example.weather.app.WeatherMachines
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
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

interface CityWeatherComponent : BaseComponent {
    val state: StateFlow<CityWeatherUiState>

    fun onUiEvent(event: CityWeatherIntent)

    sealed interface News {
        data object BackClicked : News
    }

    interface Factory {
        fun create(
            componentContext: ComponentContext,
            city: City,
            endpoint: BridgeEndpoint<Nothing, News>,
        ): CityWeatherComponent
    }
}

class DefaultCityWeatherComponent(
    componentContext: ComponentContext,
    private val endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    coroutineContext: CoroutineContext,
    private val machines: WeatherMachines,
) : CityWeatherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    override val state: StateFlow<CityWeatherUiState> = CityWeatherUiStateFlow(machines.state, machines.screen.state)

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CityWeatherIntent) {
        if (!machines.screen.state.value.isStarted) return
        when (val command = event.toCommand()) {
            is CityWeatherUiCommand.Advice -> machines.advice.dispatch(command.action)
            is CityWeatherUiCommand.Screen -> machines.screen.dispatch(command.action)
            is CityWeatherUiCommand.Forecast -> machines.hourly.dispatch(command.action)
            is CityWeatherUiCommand.Publish -> scope.launch { endpoint.news.emit(command.news) }
        }
    }

    @Composable
    override fun Render() {
        val uiState by state.collectAsUiState()
        CityWeatherScreen(uiState = uiState, onIntent = ::onUiEvent)
    }
}
