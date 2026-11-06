package actron.example.weather.feature.weather.presentation

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.domain.City
import actron.statechart.machine.*
import kotlinx.serialization.Serializable

@Serializable
data class WeatherScreenState(
    val city: City,
)

@Serializable
sealed interface WeatherScreenAction : Action {
    @Serializable data object Refresh : WeatherScreenAction
}

@Serializable
sealed interface WeatherScreenEvent : Event {
    @Serializable data object RefreshChildren : WeatherScreenEvent
}

/** The screen owns its header and requests updates; it never reads a child's internal state. */
object CityWeatherMachine {
    fun create(): ScreenMachine<WeatherScreenState, WeatherScreenAction, WeatherScreenEvent> =
        screenMachine(
            id = DefinitionId(value = "city-weather"),
            version = DefinitionVersion(value = "12"),
            initialType = WeatherScreenState::class,
        ) {
            state<WeatherScreenState> {
                onEnter { event(WeatherScreenEvent.RefreshChildren) }
                on<WeatherScreenAction.Refresh> { target { state } }
            }
        }
}
