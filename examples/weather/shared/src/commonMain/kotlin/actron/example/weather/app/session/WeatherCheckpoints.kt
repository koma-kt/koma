package actron.example.weather.app.session

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoCommand
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.LoadCurrentWeather
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.hourlyforecast.presentation.LoadHourlyForecast
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.LoadWeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.statechart.machine.BridgeMessage
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineTime

/** Immutable cut of the application, including deliveries still crossing machine boundaries. */
data class WeatherCheckpoints(
    val screen: ExecutorCheckpoint<WeatherScreenState, NoCommand>,
    val current: ExecutorCheckpoint<CurrentWeatherState, LoadCurrentWeather>,
    val hourly: ExecutorCheckpoint<HourlyForecastState, LoadHourlyForecast>,
    val advice: ExecutorCheckpoint<WeatherAdviceState, LoadWeatherAdvice>,
    val inFlight: List<BridgeMessage> = emptyList(),
) {
    fun context(): WeatherContext = WeatherContext(city = screen.snapshot.context.city, current = current.snapshot.context.phase, hourly = hourly.snapshot.context.phase, advice = advice.snapshot.context.phase)

    fun at(time: MachineTime): WeatherCheckpoints =
        copy(
            screen = screen.copy(now = maxOf(time, screen.now)),
            current = current.copy(now = maxOf(time, current.now)),
            hourly = hourly.copy(now = maxOf(time, hourly.now)),
            advice = advice.copy(now = maxOf(time, advice.now)),
        )

    fun canResume(): Boolean {
        val city = screen.snapshot.context.city
        val sameCity = listOf(current.snapshot.context.city, hourly.snapshot.context.city, advice.snapshot.context.city).all { it == city }
        val childrenStarted = listOf(current, hourly, advice).all { it.snapshot.isStarted }
        return sameCity && inFlight.isEmpty() && (screen.snapshot.isStarted.not() || childrenStarted) &&
            listOf(screen, current, hourly, advice).all { it.ending.isEmpty() && it.effects.isEmpty() }
    }
}

fun freshWeather(city: City): WeatherCheckpoints =
    WeatherCheckpoints(
        screen = ExecutorCheckpoint.initial(snapshot = CityWeatherMachine.create().initialSnapshot(context = WeatherScreenState(city = city))),
        current = ExecutorCheckpoint.initial(snapshot = CurrentWeatherMachine.create().initialSnapshot(context = CurrentWeatherState(city = city))),
        hourly = ExecutorCheckpoint.initial(snapshot = HourlyForecastMachine.create().initialSnapshot(context = HourlyForecastState(city = city))),
        advice = ExecutorCheckpoint.initial(snapshot = WeatherAdviceMachine.create().initialSnapshot(context = WeatherAdviceState(city = city))),
    )
