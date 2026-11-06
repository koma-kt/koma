package actron.example.weather.app

import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.decompose.retainedMachine
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.DefaultCitySearchComponent
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.DefaultCityWeatherComponent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlin.coroutines.CoroutineContext

/** Application composition connects feature stores to current checkpoints and optional recording. */
class DefaultCitySearchComponentFactory(
    private val repository: CitySearchRepository,
    private val coroutineContext: CoroutineContext,
    private val session: WeatherSession,
    private val settingsAction: @androidx.compose.runtime.Composable () -> Unit = {},
    private val recording: MachineRecording<CitySearchState, CitySearchAction, Unit, NoEvent> = MachineRecording { _, _, handler -> MachineDiagnostics(handler) },
) : CitySearchComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    ): CitySearchComponent {
        val store =
            componentContext.retainedMachine(
                key = "city-search",
                machine = CitySearchMachine.create(repository),
                initial = CitySearchState.Idle(),
                restoration = session.searchRestoration(),
                recording = recording,
                coroutineContext = coroutineContext,
            )
        return DefaultCitySearchComponent(componentContext, endpoint, coroutineContext, store, settingsAction)
    }
}

class DefaultCityWeatherComponentFactory(
    private val repository: WeatherRepository,
    private val hourlyRepository: HourlyForecastRepository,
    private val coroutineContext: CoroutineContext,
    private val session: WeatherSession,
    private val recordings: WeatherMachineRecordings = WeatherMachineRecordings(),
) : CityWeatherComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        city: City,
        endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    ): CityWeatherComponent {
        val machines =
            componentContext.instanceKeeper.getOrCreate(key = "city-weather") {
                WeatherMachines(
                    session = session,
                    initial = session.weatherCheckpoint(city = city),
                    currentRepository = repository,
                    hourlyRepository = hourlyRepository,
                    coroutineContext = coroutineContext,
                    recordings = recordings,
                ).also { it.start() }
            }
        return DefaultCityWeatherComponent(componentContext = componentContext, endpoint = endpoint, coroutineContext = coroutineContext, machines = machines)
    }
}
