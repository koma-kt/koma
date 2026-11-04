package actron.example.weather.feature.hourlyforecast.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
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
data class HourlyForecastState(
    val city: City,
    val phase: RequestState<HourlyForecast> = RequestState.Ready,
)

@Serializable
sealed interface HourlyForecastAction : Action {
    @Serializable data object Refresh : HourlyForecastAction

    @Serializable data object Retry : HourlyForecastAction

    @Serializable data class Loaded(
        val forecast: HourlyForecast,
    ) : HourlyForecastAction

    @Serializable data class Failed(
        val message: String,
    ) : HourlyForecastAction
}

@Serializable
data class LoadHourlyForecast(
    val city: City,
)

/** Reusable feature machine: no screen or sibling action is part of its contract. */
object HourlyForecastMachine {
    fun create(): Machine<HourlyForecastState, HourlyForecastAction, LoadHourlyForecast, NoEvent> =
        behaviorStateMachine(
            id = DefinitionId(value = "hourly-forecast"),
            version = DefinitionVersion(value = "3"),
            initial = RequestState.Ready,
            phaseOf = { state: HourlyForecastState -> state.phase },
            build = {
                val refresh: BehaviorStateBuilder<HourlyForecastState, HourlyForecastAction, LoadHourlyForecast, NoEvent>.() -> Unit = {
                    on<HourlyForecastAction.Refresh> { transitionTo(state = state.copy(phase = RequestState.Loading)) }
                    on<HourlyForecastAction.Retry> { transitionTo(state = state.copy(phase = RequestState.Loading)) }
                }
                state<RequestState.Ready>(build = refresh)
                state<RequestState.Loading>(build = {
                    refresh()
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadHourlyForecast(city = state.city))
                            awaitCancellation()
                        }
                        transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Timeout)))
                    }
                    on<HourlyForecastAction.Loaded> { action ->
                        if (action.forecast.city == state.city) {
                            transitionTo(state = state.copy(phase = RequestState.Success(value = action.forecast)))
                        }
                    }
                    on<HourlyForecastAction.Failed> { action ->
                        transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Unavailable(message = action.message))))
                    }
                })
                state<RequestState.Success<HourlyForecast>>(build = refresh)
                state<RequestState.Error>(build = refresh)
            },
        )
}
