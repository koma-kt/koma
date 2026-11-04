package actron.example.weather.feature.cities.presentation

import actron.core.Action
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.TypedMachineStateBuilder
import actron.statechart.machine.typedStateMachine
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CitySearchState(
    val query: String = "",
    val phase: RequestState<List<CityOption>> = RequestState.Idle,
    val revision: Long = 0,
)

@Serializable
sealed interface CitySearchAction : Action {
    @Serializable
    data class ChangeQuery(
        val query: String,
    ) : CitySearchAction

    @Serializable
    data class Found(
        val revision: Long,
        val results: List<CityOption>,
    ) : CitySearchAction

    @Serializable
    data class Failed(
        val revision: Long,
        val message: String,
    ) : CitySearchAction
}

@Serializable
data class SearchCitiesCommand(
    val query: String,
    val revision: Long,
)

/** Declared transitions share one graph with execution, guards and activation-owned timers. */
object CitySearchMachine {
    // Stores own snapshots and commands; this immutable definition is shared.
    private val definition: Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> =
        typedStateMachine(
            id = DefinitionId(value = "city-search"),
            // v13 declares the graph and preserves timer deadlines across checkpoints.
            version = DefinitionVersion(value = "13"),
            initial = RequestState.Idle,
            readState = { state: CitySearchState -> state.phase },
            writeState = { state, phase -> state.copy(phase = phase) },
        ) {
            state<RequestState.Idle> { queryChanges() }
            state<RequestState.QueryChanged> {
                always(transition = {
                    guard { state.query.isBlank() }
                    target { RequestState.Idle }
                })
                always(transition = {
                    target { RequestState.Debouncing }
                })
            }
            state<RequestState.Debouncing> {
                queryChanges()
                after(delay = 350.milliseconds, transition = {
                    target { RequestState.Loading }
                })
            }
            state<RequestState.Loading> {
                queryChanges()
                onEnter(block = {
                    command(command = SearchCitiesCommand(query = context.query.trim(), revision = context.revision))
                })
                on<CitySearchAction.Found>(transition = {
                    guard { action -> action.revision == state.revision }
                    target { action -> RequestState.Success(value = action.results) }
                })
                on<CitySearchAction.Failed>(transition = {
                    guard { action -> action.revision == state.revision }
                    target { action -> RequestState.Error(cause = RequestFailure.Unavailable(message = action.message)) }
                })
                after(delay = 8.seconds, transition = {
                    target { RequestState.Error(cause = RequestFailure.Timeout) }
                })
            }
            state<RequestState.Success<List<CityOption>>> { queryChanges() }
            state<RequestState.Error> { queryChanges() }
        }

    fun create(): Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> = definition
}

/** Every stable search phase accepts a replacement query and starts a fresh debounce. */
private fun TypedMachineStateBuilder<CitySearchState, RequestState<List<CityOption>>, CitySearchAction, SearchCitiesCommand, NoEvent>.queryChanges() {
    on<CitySearchAction.ChangeQuery>(transition = {
        target { RequestState.QueryChanged }
        update { action -> state.copy(query = action.query, revision = state.revision + 1) }
    })
}
