@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.continuationWork
import actron.example.weather.app.debug.retainedWeatherHistory
import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherSavedState
import actron.example.weather.app.session.WeatherSavedStateCodec
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import actron.timetravel.HistoryConfiguration
import actron.timetravel.compose.HistoryContinuation
import actron.timetravel.historyFields
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeatherHistoryTest {
    @Test
    fun startupLimitBoundsHistoryAndIsAppliedAfterProcessRestore() {
        val configuration = HistoryConfiguration(capacity = 3)
        val state = StateKeeperDispatcher()
        val context = DefaultComponentContext(LifecycleRegistry(), stateKeeper = state)
        val history = retainedWeatherHistory(context, configuration)
        traceEdits(history, listOf("P", "Pa", "Par", "Paris"))
        assertEquals(3, history.timeline.entries.value.size)
        assertEquals("Paris", history.session.frame.search.snapshot.context.query)
        assertSame(history, retainedWeatherHistory(context, configuration))
        state.register("weather-checkpoint", WeatherSavedState.serializer()) { WeatherSavedStateCodec.encode(history.session) }
        val restored = retainedWeatherHistory(DefaultComponentContext(LifecycleRegistry(), stateKeeper = StateKeeperDispatcher(state.save())), configuration)
        assertNotSame(history, restored)
        assertEquals(3, restored.capacity)
        assertEquals("Paris", restored.session.frame.search.snapshot.context.query)
        assertTrue(
            restored.timeline.entries.value
                .isEmpty(),
        )
        traceEdits(restored, listOf("B", "Be", "Ber", "Berlin"))
        assertEquals(3, restored.timeline.entries.value.size)
    }

    @Test
    fun continuationExplainsExecutorRestrictionsInsteadOfGuessingFromState() {
        val initial = WeatherFrame()
        assertEquals(expected = 0, actual = initial.continuationWork().endings + initial.continuationWork().effects)
        val blockedFrame =
            initial.copy(
                search =
                    initial.search.copy(
                        effects =
                            listOf(
                                PendingEffect(id = EffectId(value = 1), event = actron.example.weather.feature.weather.presentation.WeatherScreenEvent.RefreshChildren, policy = EffectPolicy.Transient, attempts = 0, handling = false),
                            ),
                    ),
            )
        assertFalse(actual = blockedFrame.canResume())
        val blocked = blockedFrame.continuationWork()
        assertEquals(expected = 0, actual = blocked.endings)
        assertEquals(expected = 1, actual = blocked.effects)
    }

    @Test
    fun hiddenHistoryRetainsEditsAndPinsSelectionAfterEviction() {
        val history = WeatherHistory(3)
        traceEdits(history, listOf("P", "Pa"))
        assertFalse(history.panelVisible.value)
        val selected =
            history.timeline.entries.value
                .last { it.details.changes.any { change -> change.path == "query" } }
        history.panel.select(selected)
        history.panel.previewSelected()
        traceEdits(history, listOf("Berlin", "Berlinn"))
        assertEquals(3, history.timeline.entries.value.size)
        assertEquals(selected, history.panel.preview.single())
        assertEquals("Pa", selected.snapshot.application.search.snapshot.context.query)
        assertTrue(selected.details.changes.any { it.path == "query" })
        history.timeline.clear()
        assertTrue(
            history.timeline.entries.value
                .isEmpty(),
        )
        assertEquals(selected, history.panel.preview.single())
    }

    @Test
    fun oldBranchCallbacksCannotChangeNewBranch() {
        val history = WeatherHistory()
        val machine = CitySearchMachine.create()
        val checkpoint: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand> = ExecutorCheckpoint.initial(machine.initialSnapshot(CitySearchState()))
        val observer = history.observer(machine, checkpoint, { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) })
        history.session.beginBranch(WeatherFrame())
        observer.onDecided(InputAttribution.Unattributed, MachineInput.Start(MachineTime.Zero), machine.decideExplained(checkpoint.snapshot, MachineInput.Start(MachineTime.Zero)))
        observer.onCommitted(InputAttribution.Unattributed, MachineInput.Start(MachineTime.Zero), machine.decide(checkpoint.snapshot, MachineInput.Start(MachineTime.Zero)))
        assertEquals(0, history.timeline.entries.value.size)
        assertFalse(history.session.frame.search.snapshot.isStarted)
    }

    @Test
    fun coverageAccumulatesAcrossInstancesAndBranchesFromSelectedSnapshot() {
        val history = WeatherHistory(2)
        traceEdits(history, listOf("L", "Lo", "Lon"))
        val selected =
            history.timeline.entries.value
                .first()
        val selectedGraph = history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine
        assertEquals(actron.timetravel.compose.HistoryGraphProfile.Unavailable, selectedGraph.profile)
        val selectedHits = (selectedGraph.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits
        // Entry work is a separate recorded input; the selected cut is before its final choice.
        assertEquals(expected = 5L, actual = selectedHits.values.sum())
        assertEquals(
            expected = 6L,
            actual =
                history
                    .snapshot()
                    .coverage.values
                    .single()
                    .hits.values
                    .sum(),
        )
        history.session.beginBranch(history.restoreDiagnostics(selected.snapshot))
        traceEdits(history, listOf("Paris"))
        assertEquals(
            expected = 7L,
            actual =
                history
                    .snapshot()
                    .coverage.values
                    .single()
                    .hits.values
                    .sum(),
        )
        assertEquals(expected = 5L, actual = selectedHits.values.sum(), message = "Historical coverage is immutable")
        assertEquals(expected = 5L, actual = (history.graph(selected) as actron.timetravel.compose.HistoryGraph.Machine).let { (it.coverage as actron.timetravel.compose.HistoryGraphCoverage.Recorded).hits.values.sum() })
    }

    private fun traceEdits(
        history: WeatherHistory,
        queries: List<String>,
    ) {
        val machine = CitySearchMachine.create()
        val checkpoint: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand> = ExecutorCheckpoint.initial(machine.initialSnapshot(CitySearchState()))
        val observer = history.observer(machine, checkpoint, { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) })
        val persistence =
            history.session
                .searchRestoration()
                .observers(machine, checkpoint)
                .single()
        var snapshot = checkpoint.snapshot
        val inputs = listOf<MachineInput<CitySearchAction>>(MachineInput.Start(MachineTime.Zero)) + queries.map { MachineInput.Dispatch(CitySearchAction.ChangeQuery(it), MachineTime.Zero) }

        fun record(input: MachineInput<CitySearchAction>) {
            val explained = machine.decideExplained(snapshot, input)
            persistence.onCommitted(InputAttribution.Unattributed, input, explained.decision)
            observer.onDecided(InputAttribution.Unattributed, input, explained)
            observer.onCommitted(InputAttribution.Unattributed, input, explained.decision)
            snapshot = explained.decision.snapshot
        }
        inputs.forEach { input ->
            record(input = input)
            if (snapshot.context.phase == actron.example.weather.core.mvi.RequestState.QueryChanged) {
                val next = if (snapshot.context.query.isBlank()) actron.example.weather.core.mvi.RequestState.Idle else actron.example.weather.core.mvi.RequestState.Debouncing
                record(
                    input =
                        MachineInput.EntryResult(
                            activation =
                                snapshot.pendingEntries.values
                                    .single()
                                    .activation,
                            output = EntryOutput.Transition(context = snapshot.context.copy(phase = next)),
                            now = MachineTime.Zero,
                        ),
                )
            }
        }
    }
}
