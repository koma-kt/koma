package actron.example.weather.feature.currentweather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.statechart.machine.BehaviorStateBuilder
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.behaviorStateMachine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CurrentWeatherState(
    val city: City,
    val phase: RequestState<WeatherReading> = RequestState.Ready,
)

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
            version = DefinitionVersion(value = "3"),
            initial = RequestState.Ready,
            phaseOf = { state: CurrentWeatherState -> state.phase },
            build = {
                val refresh: BehaviorStateBuilder<CurrentWeatherState, CurrentWeatherAction, LoadCurrentWeather, NoEvent>.() -> Unit = {
                    on<CurrentWeatherAction.Refresh> { transitionTo(state = state.copy(phase = RequestState.Loading)) }
                }
                state<RequestState.Ready>(build = refresh)
                state<RequestState.Loading>(build = {
                    refresh()
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadCurrentWeather(city = state.city))
                            awaitCancellation()
                        }
                        transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Timeout)))
                    }
                    on<CurrentWeatherAction.Loaded> { action ->
                        if (action.reading.city == state.city) {
                            transitionTo(state = state.copy(phase = RequestState.Success(value = action.reading)))
                        }
                    }
                    on<CurrentWeatherAction.Failed> { action ->
                        transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Unavailable(message = action.message))))
                    }
                })
                state<RequestState.Success<WeatherReading>>(build = refresh)
                state<RequestState.Error>(build = refresh)
            },
        )
}
