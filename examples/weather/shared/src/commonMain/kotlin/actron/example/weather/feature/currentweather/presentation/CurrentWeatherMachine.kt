package actron.example.weather.feature.currentweather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.behaviorStateMachine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed interface CurrentWeatherState {
    val city: City

    @Serializable
    data class Ready(
        override val city: City,
    ) : CurrentWeatherState

    @Serializable
    data class Loading(
        override val city: City,
    ) : CurrentWeatherState

    @Serializable
    data class Success(
        override val city: City,
        val reading: WeatherReading,
    ) : CurrentWeatherState

    @Serializable
    data class Error(
        override val city: City,
        val cause: RequestFailure,
    ) : CurrentWeatherState
}

fun CurrentWeatherState.toRequestState(): RequestState<WeatherReading> =
    when (this) {
        is CurrentWeatherState.Ready -> RequestState.Ready
        is CurrentWeatherState.Loading -> RequestState.Loading
        is CurrentWeatherState.Success -> RequestState.Success(reading)
        is CurrentWeatherState.Error -> RequestState.Error(cause)
    }

@Serializable
sealed interface CurrentWeatherAction : Action {
    @Serializable data object Refresh : CurrentWeatherAction

    @Serializable data class Loaded(
        val reading: WeatherReading,
    ) : CurrentWeatherAction

    @Serializable data class Failed(
        val message: String,
    ) : CurrentWeatherAction
}

@Serializable
data class LoadCurrentWeather(
    val city: City,
)

/** Reusable feature machine: no screen or sibling action is part of its contract. */
object CurrentWeatherMachine {
    fun create(): Machine<CurrentWeatherState, CurrentWeatherAction, LoadCurrentWeather, NoEvent> =
        behaviorStateMachine(
            id = DefinitionId(value = "current-weather"),
            version = DefinitionVersion(value = "4"),
            initialType = CurrentWeatherState.Ready::class,
            build = {
                on<CurrentWeatherAction.Refresh> { target(CurrentWeatherState.Loading(state.city)) }
                state<CurrentWeatherState.Ready>()
                state<CurrentWeatherState.Loading>(build = {
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadCurrentWeather(city = state.city))
                            awaitCancellation()
                        }
                        target(CurrentWeatherState.Error(state.city, cause = RequestFailure.Timeout))
                    }
                    on<CurrentWeatherAction.Loaded> { action ->
                        if (action.reading.city == state.city) {
                            target(CurrentWeatherState.Success(state.city, action.reading))
                        }
                    }
                    on<CurrentWeatherAction.Failed> { action ->
                        target(CurrentWeatherState.Error(state.city, cause = RequestFailure.Unavailable(message = action.message)))
                    }
                })
                state<CurrentWeatherState.Success>()
                state<CurrentWeatherState.Error>()
            },
        )
}
