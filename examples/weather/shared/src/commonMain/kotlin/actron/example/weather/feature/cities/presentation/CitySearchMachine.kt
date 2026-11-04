package actron.example.weather.feature.cities.presentation

import actron.core.Action
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.behaviorStateMachine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/** State-local behavior; the executor owns entry work and cancels it on state exit. */
object CitySearchMachine {
    // Stores own snapshots and entry jobs; this immutable definition is shared.
    private val definition: Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> =
        behaviorStateMachine(
            id = DefinitionId(value = "city-search"),
            version = DefinitionVersion(value = "12"),
            initial = RequestState.Idle,
            phaseOf = { state: CitySearchState -> state.phase },
        ) {
            state<RequestState.Idle> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
            }
            state<RequestState.QueryChanged> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
                onEnter {
                    transitionTo(
                        state =
                            state.copy(
                                phase =
                                    if (state.query.isBlank()) {
                                        RequestState.Idle
                                    } else {
                                        RequestState.Debouncing
                                    },
                            ),
                    )
                }
            }
            state<RequestState.Debouncing> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
                onEnter {
                    delay(duration = 350.milliseconds)
                    transitionTo(state = state.copy(phase = RequestState.Loading))
                }
            }
            state<RequestState.Loading> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
                onEnter {
                    withTimeoutOrNull<Unit>(timeout = 8.seconds) {
                        command(command = SearchCitiesCommand(query = state.query.trim(), revision = state.revision))
                        awaitCancellation()
                    }
                    transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Timeout)))
                }
                on<CitySearchAction.Found> { action ->
                    if (action.revision == state.revision) {
                        transitionTo(state = state.copy(phase = RequestState.Success(value = action.results)))
                    }
                }
                on<CitySearchAction.Failed> { action ->
                    if (action.revision == state.revision) {
                        transitionTo(state = state.copy(phase = RequestState.Error(cause = RequestFailure.Unavailable(message = action.message))))
                    }
                }
            }
            state<RequestState.Success<List<CityOption>>> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
            }
            state<RequestState.Error> {
                on<CitySearchAction.ChangeQuery> { action ->
                    transitionTo(
                        state =
                            state.copy(
                                query = action.query,
                                revision = state.revision + 1,
                                phase = RequestState.QueryChanged,
                            ),
                    )
                }
            }
        }

    fun create(): Machine<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> = definition
}
