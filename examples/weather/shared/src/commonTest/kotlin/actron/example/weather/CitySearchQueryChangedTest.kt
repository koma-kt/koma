@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CitySearchQueryChangedTest {
    @Test
    fun eachEditAutomaticallyChoosesFromUpdatedQuery() =
        runTest {
            val store =
                MachineStore(
                    machine = CitySearchMachine.create(),
                    context = CitySearchState(),
                    handler = CommandHandler { _, _ -> error("Debounce must prevent requests") },
                    scope = backgroundScope,
                    coroutineContext = StandardTestDispatcher(scheduler = testScheduler),
                )
            store.start()
            val edits =
                listOf(
                    " London " to RequestState.Debouncing,
                    " \t" to RequestState.Idle,
                    "Tokyo" to RequestState.Debouncing,
                    "" to RequestState.Idle,
                )
            for ((index, edit) in edits.withIndex()) {
                val (query, expected) = edit
                store.dispatch(action = CitySearchAction.ChangeQuery(query = query))
                runCurrent()
                assertEquals(expected = expected, actual = store.state.value.context.phase)
                assertEquals(expected = query, actual = store.state.value.context.query)
                assertEquals(expected = index + 1L, actual = store.state.value.context.revision)
                assertEquals(expected = 0, actual = testScheduler.currentTime)
            }
            store.closeAndJoin()
        }
}
