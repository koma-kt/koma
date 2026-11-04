package actron.example.weather.feature.weatheradvice.presentation

import actron.core.Action
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdvicePhase
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
data class WeatherAdviceState(
    val city: City,
    val phase: WeatherAdvicePhase = WeatherAdvicePhase.Ready,
)

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
            version = DefinitionVersion(value = "3"),
            initial = WeatherAdvicePhase.Ready,
            phaseOf = { state: WeatherAdviceState -> state.phase },
            build = {
                state<WeatherAdvicePhase.Ready>(build = { refreshAdvice() })
                state<WeatherAdvicePhase.Loading>(build = {
                    refreshAdvice()
                    onEnter {
                        withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                            command(command = LoadWeatherAdvice(city = state.city))
                            awaitCancellation()
                        }
                        transitionTo(state = state.copy(phase = WeatherAdvicePhase.Error(cause = RequestFailure.Timeout)))
                    }
                    on<WeatherAdviceAction.Loaded> { action ->
                        if (action.city == state.city) {
                            transitionTo(state = state.copy(phase = if (action.items.isEmpty()) WeatherAdvicePhase.Hidden else WeatherAdvicePhase.Visible(items = action.items)))
                        }
                    }
                    on<WeatherAdviceAction.Failed> { action ->
                        transitionTo(state = state.copy(phase = WeatherAdvicePhase.Error(cause = RequestFailure.Unavailable(message = action.message))))
                    }
                    dismissAdvice()
                })
                state<WeatherAdvicePhase.Hidden>(build = { refreshAdvice() })
                state<WeatherAdvicePhase.Visible>(build = {
                    refreshAdvice()
                    dismissAdvice()
                })
                state<WeatherAdvicePhase.Dismissed>(build = { refreshAdvice() })
                state<WeatherAdvicePhase.Error>(build = {
                    refreshAdvice()
                    dismissAdvice()
                    on<WeatherAdviceAction.Retry> { transitionTo(state = state.copy(phase = WeatherAdvicePhase.Loading)) }
                })
            },
        )

    private fun BehaviorStateBuilder<WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent>.refreshAdvice() {
        on<WeatherAdviceAction.Refresh> { transitionTo(state = state.copy(phase = WeatherAdvicePhase.Loading)) }
    }

    private fun BehaviorStateBuilder<WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent>.dismissAdvice() {
        on<WeatherAdviceAction.Dismiss> { transitionTo(state = state.copy(phase = WeatherAdvicePhase.Dismissed)) }
    }
}
