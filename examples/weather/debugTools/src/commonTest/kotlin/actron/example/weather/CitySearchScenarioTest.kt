package actron.example.weather

import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.test.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CitySearchScenarioTest {
    @Test
    fun entrySearchesTrimmedTextAndPreservesTheOriginalQuery() =
        runTest {
            val report =
                CitySearchMachine
                    .create { query ->
                        assertEquals("London", query)
                        emptyList()
                    }.test(this, CitySearchState.Idle()) {
                        expectState<CitySearchState.Idle>()
                        dispatch(CitySearchAction.ChangeQuery(" London "))
                        expectState<CitySearchState.Debouncing>()
                        advanceTimeBy(349.milliseconds)
                        expectState<CitySearchState.Debouncing>()
                        advanceTimeBy(1.milliseconds)
                        assertEquals(CitySearchState.Success(" London ", 1, emptyList()), expectState<CitySearchState.Success>())
                        dispatch(CitySearchAction.ChangeQuery(""))
                        expectState<CitySearchState.Idle>()
                    }
            assertEquals(expected = 6, actual = report.transitions.covered.size)
        }

    @Test
    fun editingAfterFailureAndClearingCancelPendingWork() =
        runTest {
            val report =
                CitySearchMachine.create { awaitCancellation() }.test(this, CitySearchState.Idle()) {
                    dispatch(CitySearchAction.ChangeQuery("L"))
                    advanceTimeBy(350.milliseconds)
                    dispatch(CitySearchAction.Failed(context.revision, "offline"))
                    assertEquals(RequestFailure.Unavailable(message = "offline"), expectState<CitySearchState.Error>().cause)
                    dispatch(CitySearchAction.ChangeQuery("Lo"))
                    advanceTimeBy(350.milliseconds)
                    expectState<CitySearchState.Loading>()
                    dispatch(CitySearchAction.ChangeQuery("Lon"))
                    advanceTimeBy(350.milliseconds)
                    dispatch(CitySearchAction.ChangeQuery(""))
                    expectState<CitySearchState.Idle>()
                }
            assertTrue(report.transitions.covered.isNotEmpty())
        }
}
