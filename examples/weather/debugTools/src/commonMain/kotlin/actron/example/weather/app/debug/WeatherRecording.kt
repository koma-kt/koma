package actron.example.weather.app.debug

import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.presentation.*
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.statechart.StateId
import actron.statechart.machine.TransitionId
import actron.timetravel.*
import actron.timetravel.compose.*
import kotlinx.serialization.Serializable

/** No checkpoints, commands, credentials, or executor handles are part of an imported snapshot. */
@Serializable
sealed interface WeatherDisplay {
    @Serializable data class Search(
        val state: CitySearchState,
    ) : WeatherDisplay

    @Serializable data class Weather(
        val state: WeatherContext,
    ) : WeatherDisplay
}

@Serializable
data class WeatherRecordingFrame(
    val screen: WeatherDisplay,
    val active: List<String>,
    val hits: Map<Int, Long>,
)

object WeatherRecording {
    private val search = CitySearchMachine.create(LocalCitySearchRepository)
    private val weather = CityWeatherMachine.create()
    private val charts = listOf(search.chart.historyDefinition(search.id.value, search.version.value), weather.chart.historyDefinition(weather.id.value, weather.version.value), CurrentWeatherMachine.create(SampleWeatherRepository).let { it.chart.historyDefinition(it.id.value, it.version.value) }, HourlyForecastMachine.create(SampleHourlyRepository).let { it.chart.historyDefinition(it.id.value, it.version.value) }, WeatherAdviceMachine.create(SampleWeatherRepository).let { it.chart.historyDefinition(it.id.value, it.version.value) })
    private val machineCharts = mapOf("city-search" to search.chart, "city-weather" to weather.chart, "current-weather" to CurrentWeatherMachine.create(SampleWeatherRepository).chart, "hourly-forecast" to HourlyForecastMachine.create(SampleHourlyRepository).chart, "weather-advice" to WeatherAdviceMachine.create(SampleWeatherRepository).chart)
    val definitions = charts

    // Display schema plus machine versions/graphs reject incompatible files before preview.
    val codec = HistoryRecordingCodec(schema = "actron.weather.display/3", serializer = WeatherRecordingFrame.serializer(), definitions = definitions)

    fun export(
        history: WeatherHistory,
        at: kotlin.time.Instant =
            kotlin.time.Clock.System
                .now(),
    ): ByteArray {
        val entries =
            history.timeline.entries.value.map { entry ->
                val frame = entry.snapshot.application
                val screen =
                    when (val route = frame.route) {
                        WeatherRoute.Search -> WeatherDisplay.Search(frame.search.snapshot.context)
                        is WeatherRoute.Weather -> WeatherDisplay.Weather(route.checkpoint.context())
                    }
                val graph = history.graph(entry)
                val active = if (graph is HistoryGraph.Machine) graph.active.map { it.value } else emptyList()
                val hits =
                    entry.snapshot.coverage.values
                        .firstOrNull { it.definition.value == entry.machine.stableId }
                        ?.hits
                        .orEmpty()
                        .mapKeys { it.key.index }
                HistoryRecord(entry.id, entry.machine, entry.branch, entry.at.toString(), entry.details, WeatherRecordingFrame(screen, active, hits))
            }
        return codec.encode(HistoryRecording(codec.schema, entries, history.exportSettings.metadata(at), definitions = definitions))
    }

    fun import(bytes: ByteArray): HistoryRecording<WeatherRecordingFrame> =
        codec.decode(bytes).also { recording ->
            recording.entries.forEach { entry ->
                val chart =
                    machineCharts[entry.machine.stableId] ?: throw IllegalArgumentException("Unknown Weather machine")
                require(entry.snapshot.active.all { chart.hasNode(StateId(it)) }) { "Unknown active state" }
                require(entry.snapshot.hits.all { (index, count) -> index in chart.transitions.indices && count > 0 }) { "Invalid transition coverage" }
            }
        }

    fun graph(entry: HistoryEntry<WeatherRecordingFrame>): HistoryGraph =
        HistoryGraph.Machine(
            machineCharts.getValue(entry.machine.stableId),
            entry.snapshot.active
                .map(::StateId)
                .toSet(),
            HistoryGraphCoverage.Recorded(entry.snapshot.hits.mapKeys { TransitionId(it.key) }),
        )
}
