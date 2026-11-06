package actron.example.weather.app.session

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.observability.FailureDescriptor
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.timetravel.RecordedStep
import actron.timetravel.carriedPast
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

sealed interface WeatherRoute {
    data object Search : WeatherRoute

    data class Weather(
        val checkpoint: WeatherCheckpoints,
    ) : WeatherRoute
}

data class WeatherFrame(
    val search: ExecutorCheckpoint<CitySearchState, Unit> = ExecutorCheckpoint.initial(CitySearchMachine.create(LocalCitySearchRepository).initialSnapshot(CitySearchState.Idle())),
    val route: WeatherRoute = WeatherRoute.Search,
) {
    fun at(time: MachineTime): WeatherFrame =
        copy(
            search = search.copy(now = maxOf(time, search.now)),
            route =
                when (val screen = route) {
                    WeatherRoute.Search -> WeatherRoute.Search
                    is WeatherRoute.Weather -> WeatherRoute.Weather(screen.checkpoint.at(time = time))
                },
        )

    fun canResume(): Boolean =
        search.ending.isEmpty() && search.effects.isEmpty() &&
            when (val screen = route) {
                WeatherRoute.Search -> true
                is WeatherRoute.Weather -> screen.checkpoint.canResume()
            }
}

/** A current checkpoint per active machine. No event history or debugger dependency. */
class WeatherSession(
    initialFrame: WeatherFrame = WeatherFrame(),
    initialBranch: Long = 0,
    private val clockFactory: () -> MachineClock = { MachineClock.monotonic() },
) : InstanceKeeper.Instance {
    private val lock = SynchronizedObject()
    private var currentClock = weatherClock(initialFrame, clockFactory)
    private var currentFrame = initialFrame
    private var weatherVisit = 0L
    private var currentBranch = initialBranch
    private var resumableFrame = initialFrame

    val clock get() = synchronized(lock) { currentClock }
    val frame get() = synchronized(lock) { currentFrame }
    val branch get() = synchronized(lock) { currentBranch }

    init {
        require(initialFrame.canResume())
    }

    /** Pause logical time while the process is absent, preserving remaining timer delays. */
    fun persistenceFrame(): WeatherFrame = synchronized(lock) { resumableFrame.at(currentClock.now()) }

    fun beginBranch(snapshot: WeatherFrame) =
        synchronized(lock) {
            require(snapshot.canResume())
            currentBranch++
            currentFrame = snapshot
            resumableFrame = snapshot
            currentClock = weatherClock(snapshot, clockFactory)
        }

    val searchCheckpoint get() = frame.search

    fun weatherCheckpoint(city: City): WeatherCheckpoints =
        when (val route = frame.route) {
            is WeatherRoute.Weather -> if (route.checkpoint.screen.snapshot.context.city == city) route.checkpoint else freshWeather(city)
            WeatherRoute.Search -> freshWeather(city)
        }

    internal fun updateSearch(checkpoint: ExecutorCheckpoint<CitySearchState, Unit>) {
        currentFrame = currentFrame.copy(search = checkpoint)
    }

    internal fun updateWeather(checkpoint: WeatherCheckpoints) {
        currentFrame = currentFrame.copy(route = WeatherRoute.Weather(checkpoint))
    }

    internal fun updateWeatherPending(messages: List<actron.statechart.machine.BridgeMessage>) {
        val route = frame.route
        if (route is WeatherRoute.Weather) currentFrame = currentFrame.copy(route = route.copy(checkpoint = route.checkpoint.copy(inFlight = messages)))
    }

    fun openCity(city: City) =
        synchronized(lock) {
            weatherVisit++
            currentFrame = currentFrame.copy(route = WeatherRoute.Weather(freshWeather(city))).at(clock.now())
            if (currentFrame.canResume()) resumableFrame = currentFrame
        }

    fun back() =
        synchronized(lock) {
            weatherVisit++
            currentFrame = currentFrame.copy(route = WeatherRoute.Search).at(clock.now())
            if (currentFrame.canResume()) resumableFrame = currentFrame
        }

    internal fun <C : Any, A : Action, CMD : Any, E : Event> observe(
        checkpoint: ExecutorCheckpoint<C, CMD>,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
        followsWeatherVisit: Boolean = false,
        afterUpdate: (RecordedStep<C, A, CMD, E>) -> Unit = {},
    ): DecisionObserver<C, A, CMD, E> {
        val generation = branch
        val visit = synchronized(lock) { weatherVisit }
        var current = checkpoint
        return object : DecisionObserver<C, A, CMD, E> {
            private fun apply(step: RecordedStep<C, A, CMD, E>) =
                synchronized(lock) {
                    if (generation != currentBranch || (followsWeatherVisit && visit != weatherVisit)) return@synchronized
                    current = current.carriedPast(step)
                    update(current)
                    afterUpdate(step)
                    currentFrame = currentFrame.at(current.now)
                    if (currentFrame.canResume()) resumableFrame = currentFrame
                }

            override fun onCommitted(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                decision: Decision<C, CMD, E>,
            ) = apply(RecordedStep.Committed(machineInput, decision))

            override fun onIgnored(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                reason: IgnoreReason,
            ) = apply(RecordedStep.Ignored(machineInput, reason))

            override fun onFailed(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                failure: FailureDescriptor,
            ) = apply(RecordedStep.Failed(machineInput, failure))
        }
    }
}

private fun weatherClock(
    snapshot: WeatherFrame,
    clockFactory: () -> MachineClock,
): MachineClock {
    val base = clockFactory()
    val baseStart = base.now()
    val origin = snapshot.search.now
    return object : MachineClock {
        override fun now(): MachineTime = origin + (base.now() - baseStart)

        override suspend fun delayUntil(deadline: MachineTime) = base.delayUntil(baseStart + (deadline - origin))
    }
}
