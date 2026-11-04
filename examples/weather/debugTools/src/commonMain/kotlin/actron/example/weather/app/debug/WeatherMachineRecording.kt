package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.app.WeatherMachineRecordings
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.timetravel.historyFields
import kotlinx.serialization.json.Json

internal fun <C : Any, A : Action, CMD : Any, E : Event> WeatherHistory.recording(): MachineRecording<C, A, CMD, E> =
    MachineRecording { machine, checkpoint, handler ->
        val observer = observer(machine, checkpoint, { describeWeatherContext(it) })
        MachineDiagnostics(handler, listOf(observer))
    }

private fun describeWeatherContext(value: Any) =
    historyFields(
        when (value) {
            is CitySearchState -> Json.encodeToJsonElement(CitySearchState.serializer(), value)
            is WeatherScreenState -> Json.encodeToJsonElement(WeatherScreenState.serializer(), value)
            is CurrentWeatherState -> Json.encodeToJsonElement(CurrentWeatherState.serializer(), value)
            is HourlyForecastState -> Json.encodeToJsonElement(HourlyForecastState.serializer(), value)
            is WeatherAdviceState -> Json.encodeToJsonElement(WeatherAdviceState.serializer(), value)
            is WeatherContext -> Json.encodeToJsonElement(WeatherContext.serializer(), value)
            else -> error("Unknown Weather context")
        },
    )

internal fun WeatherHistory.weatherRecordings(): WeatherMachineRecordings = WeatherMachineRecordings(screen = recording(), current = recording(), hourly = recording(), advice = recording())
