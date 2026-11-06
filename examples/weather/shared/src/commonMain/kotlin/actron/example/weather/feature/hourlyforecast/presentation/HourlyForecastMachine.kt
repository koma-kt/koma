package actron.example.weather.feature.hourlyforecast.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.behaviorStateMachine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
sealed interface HourlyForecastState {
    val city: City

    @Serializable
    data class Ready(
        override val city: City,
    ) : HourlyForecastState

    @Serializable
    data class Loading(
        override val city: City,
    ) : HourlyForecastState

    @Serializable
    data class Success(
        override val city: City,
        val forecast: HourlyForecast,
    ) : HourlyForecastState

    @Serializable
    data class Error(
        override val city: City,
        val cause: RequestFailure,
    ) : HourlyForecastState
}

fun HourlyForecastState.toRequestState(): RequestState<HourlyForecast> =
    when (this) {
        is HourlyForecastState.Ready -> RequestState.Ready
        is HourlyForecastState.Loading -> RequestState.Loading
        is HourlyForecastState.Success -> RequestState.Success(forecast)
        is HourlyForecastState.Error -> RequestState.Error(cause)
    }

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
            version = DefinitionVersion(value = "4"),
            initialType = HourlyForecastState.Ready::class,
            build = {
                on<HourlyForecastAction.Refresh> { target(HourlyForecastState.Loading(state.city)) }
                on<HourlyForecastAction.Retry> { target(HourlyForecastState.Loading(state.city)) }
                state<HourlyForecastState.Ready>()
                state<HourlyForecastState.Loading>(build = {
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadHourlyForecast(city = state.city))
                            awaitCancellation()
                        }
                        target(HourlyForecastState.Error(state.city, cause = RequestFailure.Timeout))
                    }
                    on<HourlyForecastAction.Loaded> { action ->
                        if (action.forecast.city == state.city) {
                            target(HourlyForecastState.Success(state.city, action.forecast))
                        }
                    }
                    on<HourlyForecastAction.Failed> { action ->
                        target(HourlyForecastState.Error(state.city, cause = RequestFailure.Unavailable(message = action.message)))
                    }
                })
                state<HourlyForecastState.Success>()
                state<HourlyForecastState.Error>()
            },
        )
}
