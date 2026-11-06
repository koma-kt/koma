package actron.example.weather.app.session

import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.presentation.*
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.statechart.machine.MachineClock
import actron.timetravel.checkpointGroup
import actron.timetravel.machinePersistence
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.serialization.Serializable

/** Only the current application checkpoint is saved; the debug timeline is session-local. */
@Serializable
data class WeatherSavedState(
    val format: Int = 3,
    val checkpoints: String,
    val branch: Long,
)

object WeatherSavedStateCodec {
    private val group =
        // Generic codecs validate all machines; these read/write adapters alone know Weather's frame.
        checkpointGroup(::WeatherFrame) {
            machine(machinePersistence(CitySearchMachine.create(LocalCitySearchRepository)), read = { it.search }, write = { frame, search -> frame.copy(search = search) })
            machines(persistence = machinePersistence(machine = CityWeatherMachine.create()), read = { frame ->
                when (val route = frame.route) {
                    WeatherRoute.Search -> emptyList()
                    is WeatherRoute.Weather -> listOf(route.checkpoint.screen)
                }
            }, write = { frame, parents ->
                frame.copy(
                    route =
                        if (parents.isEmpty()) {
                            WeatherRoute.Search
                        } else {
                            WeatherRoute.Weather(
                                checkpoint =
                                    freshWeather(
                                        city =
                                            parents
                                                .single()
                                                .snapshot.context.city,
                                    ).copy(screen = parents.single()),
                            )
                        },
                )
            })
            weatherPart(machine = CurrentWeatherMachine.create(SampleWeatherRepository), read = { it.current }, write = { cut, part -> cut.copy(current = part) })
            weatherPart(machine = HourlyForecastMachine.create(SampleHourlyRepository), read = { it.hourly }, write = { cut, part -> cut.copy(hourly = part) })
            weatherPart(machine = WeatherAdviceMachine.create(SampleWeatherRepository), read = { it.advice }, write = { cut, part -> cut.copy(advice = part) })
        }

    fun encode(history: WeatherSession): WeatherSavedState = WeatherSavedState(checkpoints = group.save(history.persistenceFrame()), branch = history.branch)

    fun decodeSession(
        saved: WeatherSavedState,
        clockFactory: () -> MachineClock = { MachineClock.monotonic() },
    ): WeatherSession {
        require(saved.format == 3 && saved.branch >= 0)
        return WeatherSession(initialFrame = group.restore(saved.checkpoints), initialBranch = saved.branch, clockFactory = clockFactory)
    }
}

fun retainedWeatherSession(context: ComponentContext): WeatherSession =
    context.instanceKeeper.getOrCreate(key = "weather-session") {
        try {
            context.stateKeeper.consume("weather-checkpoint", WeatherSavedState.serializer())?.let { saved ->
                return@getOrCreate WeatherSavedStateCodec.decodeSession(saved)
            }
        } catch (_: IllegalArgumentException) {
            return@getOrCreate WeatherSession()
        }
        WeatherSession()
    }

private inline fun <reified C : Any, reified A : actron.core.Action, reified CMD : Any, reified E : actron.core.Event> actron.timetravel.CheckpointGroupBuilder<WeatherFrame>.weatherPart(
    machine: actron.statechart.machine.Machine<C, A, CMD, E>,
    noinline read: (WeatherCheckpoints) -> actron.statechart.machine.ExecutorCheckpoint<C, CMD>,
    noinline write: (WeatherCheckpoints, actron.statechart.machine.ExecutorCheckpoint<C, CMD>) -> WeatherCheckpoints,
) {
    machines(persistence = machinePersistence(machine = machine), read = { frame ->
        when (val route = frame.route) {
            WeatherRoute.Search -> emptyList()
            is WeatherRoute.Weather -> listOf(read(route.checkpoint))
        }
    }, write = { frame, parts ->
        when (val route = frame.route) {
            WeatherRoute.Search -> {
                require(parts.isEmpty()) { "A child checkpoint needs its screen" }
                frame
            }

            is WeatherRoute.Weather -> {
                frame.copy(route = WeatherRoute.Weather(checkpoint = write(route.checkpoint, parts.single())))
            }
        }
    })
}
