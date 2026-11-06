package actron.example.weather.feature.weatheradvice.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.domain.weatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.screenMachine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
sealed interface WeatherAdviceState {
    val city: City

    @Serializable
    data class Ready(
        override val city: City,
    ) : WeatherAdviceState

    @Serializable
    data class Loading(
        override val city: City,
    ) : WeatherAdviceState

    @Serializable
    data class Hidden(
        override val city: City,
    ) : WeatherAdviceState

    @Serializable
    data class Visible(
        override val city: City,
        val items: List<WeatherAdvice>,
    ) : WeatherAdviceState

    @Serializable
    data class Dismissed(
        override val city: City,
    ) : WeatherAdviceState

    @Serializable
    data class Error(
        override val city: City,
        val cause: RequestFailure,
    ) : WeatherAdviceState
}

fun WeatherAdviceState.toAdvicePhase(): WeatherAdvicePhase =
    when (this) {
        is WeatherAdviceState.Ready -> WeatherAdvicePhase.Ready
        is WeatherAdviceState.Loading -> WeatherAdvicePhase.Loading
        is WeatherAdviceState.Hidden -> WeatherAdvicePhase.Hidden
        is WeatherAdviceState.Visible -> WeatherAdvicePhase.Visible(items)
        is WeatherAdviceState.Dismissed -> WeatherAdvicePhase.Dismissed
        is WeatherAdviceState.Error -> WeatherAdvicePhase.Error(cause)
    }

@Serializable
sealed interface WeatherAdviceAction : Action {
    @Serializable data object Refresh : WeatherAdviceAction

    @Serializable data object Retry : WeatherAdviceAction

    @Serializable data object Dismiss : WeatherAdviceAction

    @Serializable data class Loaded(
        val city: City,
        val items: List<WeatherAdvice>,
    ) : WeatherAdviceAction

    @Serializable data class Failed(
        val message: String,
    ) : WeatherAdviceAction
}

object WeatherAdviceMachine {
    fun create(repository: WeatherRepository): ScreenMachine<WeatherAdviceState, WeatherAdviceAction, NoEvent> =
        screenMachine(
            id = DefinitionId("weather-advice"),
            version = DefinitionVersion("5"),
            initialType = WeatherAdviceState.Ready::class,
        ) {
            state<WeatherAdviceState.Ready> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
            }
            state<WeatherAdviceState.Loading> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
                onEnter {
                    val result =
                        runCatching {
                            val reading = repository.load(state.city)
                            WeatherAdviceAction.Loaded(reading.city, weatherAdvice(reading))
                        }.getOrElse { error ->
                            if (error is CancellationException || error !is Exception) throw error
                            WeatherAdviceAction.Failed(error.message ?: "Advice request failed")
                        }
                    dispatch(result)
                }
                on<WeatherAdviceAction.Loaded> {
                    guard { action -> action.city == state.city && action.items.isEmpty() }
                    target { WeatherAdviceState.Hidden(state.city) }
                }
                on<WeatherAdviceAction.Loaded> {
                    guard { action -> action.city == state.city && action.items.isNotEmpty() }
                    target { action -> WeatherAdviceState.Visible(state.city, action.items) }
                }
                on<WeatherAdviceAction.Failed> { target { action -> WeatherAdviceState.Error(state.city, RequestFailure.Unavailable(action.message)) } }
                on<WeatherAdviceAction.Dismiss> { target { WeatherAdviceState.Dismissed(state.city) } }
            }
            state<WeatherAdviceState.Hidden> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
            }
            state<WeatherAdviceState.Visible> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
                on<WeatherAdviceAction.Dismiss> { target { WeatherAdviceState.Dismissed(state.city) } }
            }
            state<WeatherAdviceState.Dismissed> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
            }
            state<WeatherAdviceState.Error> {
                on<WeatherAdviceAction.Refresh> { target { WeatherAdviceState.Loading(state.city) } }
                on<WeatherAdviceAction.Retry> { target { WeatherAdviceState.Loading(state.city) } }
                on<WeatherAdviceAction.Dismiss> { target { WeatherAdviceState.Dismissed(state.city) } }
            }
        }
}
