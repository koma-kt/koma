package actron.example.weather.feature.currentweather.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.screenMachine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

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

object CurrentWeatherMachine {
    fun create(repository: WeatherRepository): ScreenMachine<CurrentWeatherState, CurrentWeatherAction, NoEvent> =
        screenMachine(
            id = DefinitionId("current-weather"),
            version = DefinitionVersion("5"),
            initialType = CurrentWeatherState.Ready::class,
        ) {
            state<CurrentWeatherState.Ready> {
                on<CurrentWeatherAction.Refresh> { target { CurrentWeatherState.Loading(state.city) } }
            }
            state<CurrentWeatherState.Loading> {
                on<CurrentWeatherAction.Refresh> { target { CurrentWeatherState.Loading(state.city) } }
                onEnter {
                    val result =
                        runCatching {
                            CurrentWeatherAction.Loaded(reading = repository.load(state.city))
                        }.getOrElse { error ->
                            if (error is CancellationException || error !is Exception) throw error
                            CurrentWeatherAction.Failed(error.message ?: "Weather request failed")
                        }
                    dispatch(result)
                }
                on<CurrentWeatherAction.Loaded> {
                    guard { action -> action.reading.city == state.city }
                    target { action -> CurrentWeatherState.Success(state.city, action.reading) }
                }
                on<CurrentWeatherAction.Failed> { target { action -> CurrentWeatherState.Error(state.city, RequestFailure.Unavailable(action.message)) } }
            }
            state<CurrentWeatherState.Success> {
                on<CurrentWeatherAction.Refresh> { target { CurrentWeatherState.Loading(state.city) } }
            }
            state<CurrentWeatherState.Error> {
                on<CurrentWeatherAction.Refresh> { target { CurrentWeatherState.Loading(state.city) } }
            }
        }
}
