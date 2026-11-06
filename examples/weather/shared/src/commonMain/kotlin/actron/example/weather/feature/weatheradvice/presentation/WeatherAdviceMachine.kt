package actron.example.weather.feature.weatheradvice.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.behaviorStateMachine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

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

@Serializable
data class LoadWeatherAdvice(
    val city: City,
)

object WeatherAdviceMachine {
    fun create(): Machine<WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent> =
        behaviorStateMachine(
            id = DefinitionId(value = "weather-advice"),
            version = DefinitionVersion(value = "4"),
            initialType = WeatherAdviceState.Ready::class,
            build = {
                on<WeatherAdviceAction.Refresh> { target(WeatherAdviceState.Loading(state.city)) }
                state<WeatherAdviceState.Ready>()
                state<WeatherAdviceState.Loading>(build = {
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadWeatherAdvice(city = state.city))
                            awaitCancellation()
                        }
                        target(WeatherAdviceState.Error(state.city, cause = RequestFailure.Timeout))
                    }
                    on<WeatherAdviceAction.Loaded> { action ->
                        if (action.city == state.city) {
                            target(if (action.items.isEmpty()) WeatherAdviceState.Hidden(state.city) else WeatherAdviceState.Visible(state.city, action.items))
                        }
                    }
                    on<WeatherAdviceAction.Failed> { action ->
                        target(WeatherAdviceState.Error(state.city, cause = RequestFailure.Unavailable(message = action.message)))
                    }
                    on<WeatherAdviceAction.Dismiss> { target(WeatherAdviceState.Dismissed(state.city)) }
                })
                state<WeatherAdviceState.Hidden>()
                state<WeatherAdviceState.Visible>(build = {
                    on<WeatherAdviceAction.Dismiss> { target(WeatherAdviceState.Dismissed(state.city)) }
                })
                state<WeatherAdviceState.Dismissed>()
                state<WeatherAdviceState.Error>(build = {
                    on<WeatherAdviceAction.Dismiss> { target(WeatherAdviceState.Dismissed(state.city)) }
                    on<WeatherAdviceAction.Retry> { target(WeatherAdviceState.Loading(state.city)) }
                })
            },
        )
}
