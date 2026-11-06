package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.app.session.WeatherSavedStateCodec
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.statechart.machine.DecisionTraceObserver
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WeatherProductionSessionTest {
    @Test
    fun currentStateUpdatesWithoutExplainingOrSerializingHistory() {
        val session = WeatherSession()
        val machine = CitySearchMachine.create(LocalCitySearchRepository)
        val checkpoint = session.searchCheckpoint
        val observer = session.searchRestoration().observers(machine, checkpoint).single()
        assertFalse(observer is DecisionTraceObserver<*, *, *, *>)
        var snapshot = checkpoint.snapshot

        fun decide(input: MachineInput<CitySearchAction>) {
            val decision = machine.decide(snapshot, input)
            observer.onCommitted(InputAttribution.Unattributed, input, decision)
            snapshot = decision.snapshot
        }
        decide(MachineInput.Start(MachineTime.Zero))
        repeat(1000) { decide(MachineInput.Dispatch(CitySearchAction.ChangeQuery("City $it"), MachineTime.Zero)) }
        assertEquals("City 999", session.frame.search.snapshot.context.query)
        val restored = WeatherSavedStateCodec.decodeSession(WeatherSavedStateCodec.encode(session))
        assertEquals(session.frame.search.snapshot, restored.frame.search.snapshot)
    }
}
