package actron.example.weather

import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.statechart.Trigger
import actron.statechart.test.MachineTestDriver
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CitySearchGraphTest {
    @Test
    fun graphContainsOnlyDeclaredBusinessEdges() {
        val edges = CitySearchMachine.create().chart.transitions
        assertEquals(expected = 11, actual = edges.size)
        assertEquals(expected = 2, actual = edges.count { it.trigger == Trigger.Eventless })
        assertEquals(expected = 2, actual = edges.count { it.trigger is Trigger.After })
        val actions =
            edges.filter { it.trigger is Trigger.OnAction }.map { edge ->
                val trigger = edge.trigger as Trigger.OnAction
                "${edge.source.value}:${trigger.matcher.name}->${edge.target.value}"
            }
        assertEquals(expected = setOf("Idle:ChangeQuery->QueryChanged", "Debouncing:ChangeQuery->QueryChanged", "Loading:ChangeQuery->QueryChanged", "Success:ChangeQuery->QueryChanged", "Error:ChangeQuery->QueryChanged", "Loading:Found->Success", "Loading:Failed->Error"), actual = actions.toSet())
    }

    @Test
    fun guardCoverageRecordsStaleAndCurrentReplies() =
        runTest {
            val report =
                CitySearchMachine.create().test(scope = this, initial = CitySearchState.Idle()) {
                    dispatch(action = CitySearchAction.ChangeQuery(query = "Paris"))
                    advanceTimeBy(duration = 350.milliseconds)
                    dispatch(action = CitySearchAction.Found(revision = 0, results = emptyList()))
                    dispatch(action = CitySearchAction.Failed(revision = 0, message = "old"))
                    expectState<CitySearchState.Loading>()
                    completeCommand(result = { CitySearchAction.Found(revision = it.revision, results = emptyList()) })
                    dispatch(action = CitySearchAction.ChangeQuery(query = "London"))
                    advanceTimeBy(duration = 350.milliseconds)
                    completeCommand(result = { CitySearchAction.Failed(revision = it.revision, message = "offline") })
                }
            val edges = CitySearchMachine.create().chart.transitions
            val responses =
                edges.indices.filter { index ->
                    val trigger = edges[index].trigger
                    trigger is Trigger.OnAction && trigger.matcher.name in setOf("Found", "Failed")
                }
            for (index in responses) {
                assertEquals(
                    expected = setOf(false, true),
                    actual =
                        report.guards.covered
                            .filter { it.transition.index == index }
                            .map { it.result }
                            .toSet(),
                )
            }
            assertEquals(expected = 2, actual = responses.size)
        }

    @Test
    fun restoringDebouncePreservesRemainingTime() =
        checkRestoration(
            elapsed = 200.milliseconds,
            remaining = 150.milliseconds,
            before = CitySearchState.Debouncing("Paris", 1),
            after = CitySearchState.Loading("Paris", 1),
            commands = 1,
        )

    @Test
    fun restoringLoadingPreservesRemainingTimeout() =
        checkRestoration(
            elapsed = 8150.milliseconds,
            remaining = 200.milliseconds,
            before = CitySearchState.Loading("Paris", 1),
            after = CitySearchState.Error("Paris", 1, cause = RequestFailure.Timeout),
            commands = 0,
        )

    private fun checkRestoration(
        elapsed: Duration,
        remaining: Duration,
        before: CitySearchState,
        after: CitySearchState,
        commands: Int,
    ) = runTest {
        val machine = CitySearchMachine.create()
        val original = MachineTestDriver(machine = machine, context = CitySearchState.Idle(), scope = this)
        val checkpoint =
            try {
                original.start()
                original.send(action = CitySearchAction.ChangeQuery(query = "Paris"))
                original.advanceBy(duration = elapsed)
            } finally {
                original.close()
            }
        val resumed = MachineTestDriver(machine = machine, context = CitySearchState.Idle(), scope = this, checkpoint = checkpoint)
        try {
            resumed.start()
            resumed.advanceBy(duration = remaining - 1.milliseconds)
            assertEquals(expected = before, actual = resumed.snapshot.context)
            resumed.advanceBy(duration = 1.milliseconds)
            assertEquals(expected = after, actual = resumed.snapshot.context)
            assertEquals(expected = commands, actual = resumed.runningCommands.size)
            resumed.send(action = CitySearchAction.ChangeQuery(query = ""))
            resumed.assertNoPendingWork()
        } finally {
            resumed.close()
        }
    }
}
