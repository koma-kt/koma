package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.statechart.machine.*
import actron.timetravel.*
import actron.timetravel.compose.HistoryGraph
import actron.timetravel.compose.HistoryGraphCoverage
import actron.timetravel.compose.HistoryPanelState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/** Immutable diagnostic projection; application checkpoints remain owned by WeatherSession. */
data class WeatherHistoryFrame(
    val application: WeatherFrame,
    val coverage: Map<DefinitionId, TransitionCoverage> = emptyMap(),
)

/** Owns recording and diagnostic metadata; navigation and checkpoints belong to the session. */
class WeatherHistory(
    val capacity: Int = 500,
    val exportSettings: HistoryExportSettings = HistoryExportSettings("Weather"),
    val session: WeatherSession = WeatherSession(),
) : com.arkivanov.essenty.instancekeeper.InstanceKeeper.Instance {
    private val lock = SynchronizedObject()
    private var coverage: Map<DefinitionId, TransitionCoverage> = emptyMap()

    fun snapshot(): WeatherHistoryFrame = synchronized(lock) { WeatherHistoryFrame(session.frame, coverage) }

    fun beginBranch(snapshot: WeatherHistoryFrame): WeatherFrame =
        synchronized(lock) {
            session.beginBranch(snapshot.application)
            coverage = snapshot.coverage
            snapshot.application
        }

    val timeline = HistoryTimeline<WeatherHistoryFrame>(capacity)
    val panel = HistoryPanelState<WeatherHistoryFrame>()
    val panelLayout = actron.timetravel.compose.HistoryPanelLayoutState()
    val panelVisible = MutableStateFlow(false)
    private val searchChart = CitySearchMachine.create(LocalCitySearchRepository).chart
    private val weatherCharts =
        mapOf(
            "city-weather" to CityWeatherMachine.create().chart,
            "current-weather" to CurrentWeatherMachine.create(SampleWeatherRepository).chart,
            "hourly-forecast" to HourlyForecastMachine.create(SampleHourlyRepository).chart,
            "weather-advice" to WeatherAdviceMachine.create(SampleWeatherRepository).chart,
        )

    fun graph(entry: HistoryEntry<WeatherHistoryFrame>): HistoryGraph =
        when (entry.machine.stableId) {
            "city-search" -> {
                HistoryGraph.Machine(
                    searchChart,
                    entry.snapshot.application.search.snapshot.configuration.active
                        .toSet(),
                    HistoryGraphCoverage.Recorded(
                        entry.snapshot.coverage[DefinitionId("city-search")]
                            ?.hits
                            .orEmpty(),
                    ),
                )
            }

            "city-weather", "current-weather", "hourly-forecast", "weather-advice" -> {
                when (val route = entry.snapshot.application.route) {
                    WeatherRoute.Search -> {
                        HistoryGraph.Unavailable
                    }

                    is WeatherRoute.Weather -> {
                        HistoryGraph.Machine(
                            weatherCharts.getValue(entry.machine.stableId),
                            when (entry.machine.stableId) {
                                "current-weather" -> {
                                    route.checkpoint.current.snapshot.configuration.active
                                        .toSet()
                                }

                                "hourly-forecast" -> {
                                    route.checkpoint.hourly.snapshot.configuration.active
                                        .toSet()
                                }

                                "weather-advice" -> {
                                    route.checkpoint.advice.snapshot.configuration.active
                                        .toSet()
                                }

                                else -> {
                                    route.checkpoint.screen.snapshot.configuration.active
                                        .toSet()
                                }
                            },
                            HistoryGraphCoverage.Recorded(
                                entry.snapshot.coverage[DefinitionId(entry.machine.stableId)]
                                    ?.hits
                                    .orEmpty(),
                            ),
                        )
                    }
                }
            }

            else -> {
                HistoryGraph.Unavailable
            }
        }

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> List<StateField>,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val identity =
            timeline.machine(
                when (machine.id.value) {
                    "city-search" -> "City Search"
                    "city-weather" -> "City Weather"
                    "current-weather" -> "Current Weather"
                    "hourly-forecast" -> "Hourly Forecast"
                    "weather-advice" -> "Weather Advice"
                    else -> machine.id.value
                },
                stableId = machine.id.value,
            )
        val generation = session.branch
        // The per-instance ring buffer retains executable decisions; timeline stores host frames.
        val recorder = BoundedMachineRecorder(machine, checkpoint, capacity)
        var previous = checkpoint
        return object : DecisionTraceObserver<C, A, CMD, E> {
            // Trace precedes commit. Append only after the independent session observer updated its checkpoint.
            private var pending: () -> Unit = {}

            private fun committed() {
                val append = pending
                pending = {}
                synchronized(lock) { append() }
            }

            override fun onCommitted(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                decision: Decision<C, CMD, E>,
            ) = committed()

            override fun onIgnored(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                reason: IgnoreReason,
            ) = committed()

            override fun onFailed(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                failure: actron.observability.FailureDescriptor,
            ) = committed()

            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                pending = append@{
                    if (generation != session.branch) return@append
                    val decision = explained.decision
                    val step: RecordedStep<C, A, CMD, E> =
                        when (val outcome = decision.outcome) {
                            DecisionOutcome.Handled -> {
                                recorder.onCommitted(input, machineInput, decision)
                                RecordedStep.Committed(machineInput, decision)
                            }

                            is DecisionOutcome.Ignored -> {
                                recorder.onIgnored(input, machineInput, outcome.reason)
                                RecordedStep.Ignored(machineInput, outcome.reason)
                            }

                            is DecisionOutcome.Failed -> {
                                recorder.onFailed(input, machineInput, outcome.failure)
                                RecordedStep.Failed(machineInput, outcome.failure)
                            }
                        }
                    // Apply the recorded outcome to checkpoint bookkeeping without executing IO again.
                    val after = previous.carriedPast(step)
                    val nextCoverage = coverage.getOrElse(machine.id) { TransitionCoverage(machine.id, machine.version) }.record(decision)
                    coverage = coverage + (machine.id to nextCoverage)

                    fun fields(value: MachineSnapshot<C>) = listOf(StateField("active", value.configuration.active.joinToString()), StateField("revision", value.revision.toString())) + describe(value.context)
                    val details =
                        HistoryDetails(
                            input =
                                when (machineInput) {
                                    is MachineInput.Dispatch -> machineInput.action.toString()
                                    is MachineInput.CommandResult -> machineInput.action.toString()
                                    is MachineInput.BridgeReceived -> machineInput.action.toString()
                                    else -> machineInput.toString()
                                },
                            source =
                                when (machineInput) {
                                    is MachineInput.Dispatch -> "Action"
                                    is MachineInput.TimerFired -> "Timer"
                                    is MachineInput.CommandResult -> "Command result"
                                    is MachineInput.BridgeReceived -> "Bridge"
                                    else -> "Runtime"
                                },
                            outcome = decision.outcome.toString(),
                            before = fields(previous.snapshot),
                            after = fields(after.snapshot),
                            operations =
                                decision.commands.map { "Start ${it.command}" } + decision.timersScheduled.map { "Schedule $it" } +
                                    decision.timersCancelled.map { "Cancel timer $it" } + decision.cancelledScopes.map { "Cancel scope $it" },
                            explanation = explained.explanation.describe(),
                        )
                    previous = after
                    val index = recorder.nextStep
                    // Global eviction trims the corresponding recorder too, bounding both histories.
                    timeline.append(identity, generation, details, snapshot(), discard = { recorder.discardBefore(index) })
                }
            }
        }
    }
}
