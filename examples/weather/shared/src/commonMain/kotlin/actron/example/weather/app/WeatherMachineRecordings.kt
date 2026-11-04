package actron.example.weather.app

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.core.mvi.NoCommand
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.LoadCurrentWeather
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.hourlyforecast.presentation.LoadHourlyForecast
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weather.presentation.WeatherScreenEvent
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.LoadWeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState

class WeatherMachineRecordings(
    val screen: MachineRecording<WeatherScreenState, WeatherScreenAction, NoCommand, WeatherScreenEvent> = noRecording(),
    val current: MachineRecording<CurrentWeatherState, CurrentWeatherAction, LoadCurrentWeather, NoEvent> = noRecording(),
    val hourly: MachineRecording<HourlyForecastState, HourlyForecastAction, LoadHourlyForecast, NoEvent> = noRecording(),
    val advice: MachineRecording<WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, NoEvent> = noRecording(),
)

private fun <C : Any, A : Action, CMD : Any, E : Event> noRecording(): MachineRecording<C, A, CMD, E> = MachineRecording { _, _, handler -> MachineDiagnostics(handler = handler) }
