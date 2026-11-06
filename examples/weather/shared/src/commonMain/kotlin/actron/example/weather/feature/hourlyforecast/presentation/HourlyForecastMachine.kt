package actron.example.weather.feature.hourlyforecast.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.screenMachine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

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

object HourlyForecastMachine {
    fun create(repository: HourlyForecastRepository): ScreenMachine<HourlyForecastState, HourlyForecastAction, NoEvent> =
        screenMachine(
            id = DefinitionId("hourly-forecast"),
            version = DefinitionVersion("5"),
            initialType = HourlyForecastState.Ready::class,
        ) {
            state<HourlyForecastState.Ready> {
                on<HourlyForecastAction.Refresh> { target { HourlyForecastState.Loading(state.city) } }
                on<HourlyForecastAction.Retry> { target { HourlyForecastState.Loading(state.city) } }
            }
            state<HourlyForecastState.Loading> {
                on<HourlyForecastAction.Refresh> { target { HourlyForecastState.Loading(state.city) } }
                on<HourlyForecastAction.Retry> { target { HourlyForecastState.Loading(state.city) } }
                onEnter {
                    val result =
                        runCatching {
                            HourlyForecastAction.Loaded(forecast = repository.load(state.city))
                        }.getOrElse { error ->
                            if (error is CancellationException || error !is Exception) throw error
                            HourlyForecastAction.Failed(error.message ?: "Could not load hourly forecast")
                        }
                    dispatch(result)
                }
                on<HourlyForecastAction.Loaded> {
                    guard { action -> action.forecast.city == state.city }
                    target { action -> HourlyForecastState.Success(state.city, action.forecast) }
                }
                on<HourlyForecastAction.Failed> { target { action -> HourlyForecastState.Error(state.city, RequestFailure.Unavailable(action.message)) } }
            }
            state<HourlyForecastState.Success> {
                on<HourlyForecastAction.Refresh> { target { HourlyForecastState.Loading(state.city) } }
                on<HourlyForecastAction.Retry> { target { HourlyForecastState.Loading(state.city) } }
            }
            state<HourlyForecastState.Error> {
                on<HourlyForecastAction.Refresh> { target { HourlyForecastState.Loading(state.city) } }
                on<HourlyForecastAction.Retry> { target { HourlyForecastState.Loading(state.city) } }
            }
        }
}
