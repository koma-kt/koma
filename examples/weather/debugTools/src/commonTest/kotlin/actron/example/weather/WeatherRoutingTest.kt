package actron.example.weather

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.app.WeatherMachineRecordings
import actron.example.weather.app.WeatherMachines
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.freshWeather
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WeatherRoutingTest {
    @Test
    fun oneScreenEventBecomesThreeTypedBridgeInputsWithTheSameMessageIdentity() =
        runTest(testBody = {
            val screenInputs = mutableListOf<MachineInput<WeatherScreenAction>>()
            val currentInputs = mutableListOf<MachineInput<CurrentWeatherAction>>()
            val hourlyInputs = mutableListOf<MachineInput<HourlyForecastAction>>()
            val adviceInputs = mutableListOf<MachineInput<WeatherAdviceAction>>()
            val machines =
                WeatherMachines(
                    session = WeatherSession(clockFactory = { MachineClock.monotonic(timeSource = testScheduler.timeSource) }),
                    initial = freshWeather(city = demoCities.first()),
                    currentRepository = WeatherRepository { awaitCancellation() },
                    hourlyRepository = HourlyForecastRepository { awaitCancellation() },
                    coroutineContext = coroutineContext,
                    recordings = WeatherMachineRecordings(screen = recordInputs(inputs = screenInputs), current = recordInputs(inputs = currentInputs), hourly = recordInputs(inputs = hourlyInputs), advice = recordInputs(inputs = adviceInputs)),
                )
            try {
                machines.start()
                testScheduler.runCurrent()
                machines.screen.dispatch(action = WeatherScreenAction.Refresh)
                testScheduler.runCurrent()
                assertEquals(expected = listOf(WeatherScreenAction.Refresh), actual = screenInputs.filterIsInstance<MachineInput.Dispatch<WeatherScreenAction>>().map { it.action })
                val current = currentInputs.filterIsInstance<MachineInput.BridgeReceived<CurrentWeatherAction>>()
                val hourly = hourlyInputs.filterIsInstance<MachineInput.BridgeReceived<HourlyForecastAction>>()
                val advice = adviceInputs.filterIsInstance<MachineInput.BridgeReceived<WeatherAdviceAction>>()
                assertEquals(expected = 2, actual = current.size)
                assertEquals(expected = 2, actual = hourly.size)
                assertEquals(expected = 2, actual = advice.size)
                for (index in 0..1) {
                    assertEquals(expected = CurrentWeatherAction.Refresh, actual = current[index].action)
                    assertEquals(expected = HourlyForecastAction.Refresh, actual = hourly[index].action)
                    assertEquals(expected = WeatherAdviceAction.Refresh, actual = advice[index].action)
                    assertEquals(expected = current[index].message, actual = hourly[index].message)
                    assertEquals(expected = current[index].message, actual = advice[index].message)
                }
                assertIs<MachineInput.Start>(value = currentInputs.first())
                assertIs<MachineInput.Start>(value = hourlyInputs.first())
                assertIs<MachineInput.Start>(value = adviceInputs.first())
            } finally {
                machines.onDestroy()
            }
        })

    @Test
    fun aChildFromAnotherCityCannotBeResumedAsPartOfThisScreen() {
        val cut = freshWeather(city = demoCities.first())
        val wrong =
            cut.copy(
                current =
                    cut.current.copy(
                        snapshot =
                            cut.current.snapshot.copy(
                                context =
                                    actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
                                        .Ready(demoCities[1]),
                            ),
                    ),
            )
        assertFalse(actual = wrong.canResume())
    }
}

private fun <C : Any, A : Action, CMD : Any, E : Event> recordInputs(inputs: MutableList<MachineInput<A>>): MachineRecording<C, A, CMD, E> =
    MachineRecording { _, _, handler ->
        MachineDiagnostics(
            handler = handler,
            observers =
                listOf(
                    object : DecisionObserver<C, A, CMD, E> {
                        override fun onCommitted(
                            input: InputAttribution,
                            machineInput: MachineInput<A>,
                            decision: Decision<C, CMD, E>,
                        ) {
                            inputs.add(element = machineInput)
                        }
                    },
                ),
        )
    }
