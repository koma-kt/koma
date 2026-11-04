package actron.example.weather.app

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineRecording
import actron.example.weather.app.session.WeatherCheckpoints
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.LoadCurrentWeather
import actron.example.weather.feature.currentweather.presentation.currentWeatherHandler
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.hourlyforecast.presentation.LoadHourlyForecast
import actron.example.weather.feature.hourlyforecast.presentation.hourlyForecastHandler
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weather.presentation.WeatherScreenEvent
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.LoadWeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.example.weather.feature.weatheradvice.presentation.weatherAdviceHandler
import actron.observability.StoreInstanceId
import actron.statechart.machine.BridgeMessage
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MessageId
import actron.statechart.machine.resumeMachineStore
import actron.timetravel.RecordedStep
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/** The retained screen owns four stores and their routes; each child retains its own contract. */
class WeatherMachines(
    private val session: WeatherSession,
    initial: WeatherCheckpoints,
    currentRepository: WeatherRepository,
    hourlyRepository: HourlyForecastRepository,
    private val coroutineContext: CoroutineContext,
    recordings: WeatherMachineRecordings = WeatherMachineRecordings(),
) : InstanceKeeper.Instance {
    init {
        require(initial.canResume()) { "Weather needs a settled checkpoint for one city" }
    }

    private val scope = CoroutineScope(context = coroutineContext + SupervisorJob())
    private val group = MachineGroup()
    private val closeStores = mutableListOf<() -> Unit>()
    private val screenMember = group.member<WeatherScreenState, WeatherScreenAction, actron.example.weather.core.mvi.NoCommand, WeatherScreenEvent>(id = StoreInstanceId(value = "weather-screen"))
    private val currentMember = group.member<CurrentWeatherState, CurrentWeatherAction, LoadCurrentWeather, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-current"))
    private val hourlyMember = group.member<HourlyForecastState, HourlyForecastAction, LoadHourlyForecast, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-hourly"))
    private val adviceMember = group.member<WeatherAdviceState, WeatherAdviceAction, LoadWeatherAdvice, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-advice"))
    private val city = initial.screen.snapshot.context.city

    val current = createStore(machine = CurrentWeatherMachine.create(), checkpoint = initial.current, handler = currentWeatherHandler(repository = currentRepository), member = currentMember, recording = recordings.current, update = { session.updateWeather(checkpoint = session.weatherCheckpoint(city = city).copy(current = it)) })
    val hourly = createStore(machine = HourlyForecastMachine.create(), checkpoint = initial.hourly, handler = hourlyForecastHandler(repository = hourlyRepository), member = hourlyMember, recording = recordings.hourly, update = { session.updateWeather(checkpoint = session.weatherCheckpoint(city = city).copy(hourly = it)) })
    val advice = createStore(machine = WeatherAdviceMachine.create(), checkpoint = initial.advice, handler = weatherAdviceHandler(repository = currentRepository), member = adviceMember, recording = recordings.advice, update = { session.updateWeather(checkpoint = session.weatherCheckpoint(city = city).copy(advice = it)) })
    val screen =
        createStore(machine = CityWeatherMachine.create(), checkpoint = initial.screen, handler = CommandHandler { _, _ -> }, member = screenMember, recording = recordings.screen, update = { session.updateWeather(checkpoint = session.weatherCheckpoint(city = city).copy(screen = it)) }, pending = { step ->
            if (step is RecordedStep.Committed) {
                // Observers run before group delivery. This immutable cut includes committed routing
                // intents; child observers then project the group's actual in-flight messages.
                step.decision.effects.flatMap(transform = { effect ->
                    group.routes.filter(predicate = { it.from == screenMember.id }).map(transform = { route -> BridgeMessage(id = MessageId(from = screenMember.id, effect = effect.id), to = route.to) })
                })
            } else {
                group.inFlight
            }
        })

    val state =
        combine(flow = screen.state, flow2 = current.state, flow3 = hourly.state, flow4 = advice.state, transform = { parent, current, hourly, advice ->
            WeatherContext(city = parent.context.city, current = current.context.phase, hourly = hourly.context.phase, advice = advice.context.phase)
        }).stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = initial.context())

    init {
        group.route(from = screenMember, to = currentMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(CurrentWeatherAction.Refresh)
            }
        })
        group.route(from = screenMember, to = hourlyMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(HourlyForecastAction.Refresh)
            }
        })
        group.route(from = screenMember, to = adviceMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(WeatherAdviceAction.Refresh)
            }
        })
    }

    fun start() {
        current.start()
        hourly.start()
        advice.start()
        screen.start()
    }

    override fun onDestroy() {
        closeStores.asReversed().forEach { close -> close() }
        scope.cancel()
    }

    private fun <C : Any, A : Action, CMD : Any, E : Event> createStore(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        handler: CommandHandler<CMD, A>,
        member: MachineGroup.Member<C, A, CMD, E>,
        recording: MachineRecording<C, A, CMD, E>,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
        pending: (RecordedStep<C, A, CMD, E>) -> List<BridgeMessage> = { group.inFlight },
    ): MachineStore<C, A, CMD, E> =
        runCatching {
            val setup = recording.prepare(machine = machine, checkpoint = checkpoint, handler = handler)
            val observers = listOf(member, session.observe(checkpoint = checkpoint, update = update, afterUpdate = { step -> session.updateWeatherPending(messages = pending(step)) })) + setup.observers
            val store =
                if (checkpoint.snapshot.isStarted) {
                    resumeMachineStore(machine = machine, checkpoint = checkpoint, handler = setup.handler, scope = scope, coroutineContext = coroutineContext, clock = session.clock, observers = observers, builder = setup.configure)
                } else {
                    MachineStore(machine = machine, context = checkpoint.snapshot.context, handler = setup.handler, scope = scope, clock = session.clock, coroutineContext = coroutineContext, observers = observers, builder = setup.configure)
                }
            closeStores.add(element = { store.close() })
            member.attach(store = store)
            store
        }.getOrElse { error ->
            onDestroy()
            throw error
        }
}
