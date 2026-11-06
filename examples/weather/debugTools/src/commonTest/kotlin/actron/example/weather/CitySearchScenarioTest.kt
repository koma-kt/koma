package actron.example.weather

import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CitySearchScenarioTest {
    @Test
    fun searchSuccessUsesVirtualTimeAndScriptedCommand() =
        runTest {
            val report =
                // The DSL owns a real queued Store with scripted IO and closes it after assertions.
                CitySearchMachine.create().test(this, CitySearchState.Idle()) {
                    expectState<CitySearchState.Idle>()
                    dispatch(CitySearchAction.ChangeQuery("London"))
                    expectState<CitySearchState.Debouncing>()
                    advanceTimeBy(349.milliseconds)
                    expectState<CitySearchState.Debouncing>()
                    advanceTimeBy(1.milliseconds)
                    expectState<CitySearchState.Loading>()
                    completeCommand { command ->
                        assertEquals("London", command.query)
                        CitySearchAction.Found(command.revision, emptyList())
                    }
                    expectState<CitySearchState.Success>()
                    dispatch(CitySearchAction.ChangeQuery(""))
                    expectState<CitySearchState.Idle>()
                }
            assertEquals(expected = 6, actual = report.transitions.covered.size)
        }

    @Test
    fun errorTimeoutAndClearCancelPendingWork() =
        runTest {
            val report =
                CitySearchMachine.create().test(this, CitySearchState.Idle()) {
                    dispatch(CitySearchAction.ChangeQuery("L"))
                    advanceTimeBy(350.milliseconds)
                    completeCommand { CitySearchAction.Failed(it.revision, "offline") }
                    assertEquals(RequestFailure.Unavailable(message = "offline"), expectState<CitySearchState.Error>().cause)
                    dispatch(CitySearchAction.ChangeQuery("Lo"))
                    advanceTimeBy(350.milliseconds)
                    advanceTimeBy(8.seconds)
                    assertEquals(RequestFailure.Timeout, expectState<CitySearchState.Error>().cause)
                    dispatch(CitySearchAction.ChangeQuery("Lon"))
                    advanceTimeBy(350.milliseconds)
                    dispatch(CitySearchAction.ChangeQuery(""))
                    expectState<CitySearchState.Idle>()
                }
            assertTrue(report.transitions.covered.isNotEmpty())
        }
}
