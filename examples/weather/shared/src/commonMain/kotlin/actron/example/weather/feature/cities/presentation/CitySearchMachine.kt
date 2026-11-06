package actron.example.weather.feature.cities.presentation

import actron.core.Action
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ScreenMachine
import actron.statechart.machine.screenMachine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
sealed interface CitySearchState {
    val query: String
    val revision: Long

    @Serializable
    data class Idle(
        override val query: String = "",
        override val revision: Long = 0,
    ) : CitySearchState

    @Serializable
    data class QueryChanged(
        override val query: String,
        override val revision: Long,
    ) : CitySearchState

    @Serializable
    data class Debouncing(
        override val query: String,
        override val revision: Long,
    ) : CitySearchState

    @Serializable
    data class Loading(
        override val query: String,
        override val revision: Long,
    ) : CitySearchState

    @Serializable
    data class Success(
        override val query: String,
        override val revision: Long,
        val results: List<CityOption>,
    ) : CitySearchState

    @Serializable
    data class Error(
        override val query: String,
        override val revision: Long,
        val cause: RequestFailure,
    ) : CitySearchState
}

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

/** Declared transitions share one graph with execution, guards and activation-owned timers. */
object CitySearchMachine {
    fun create(repository: CitySearchRepository): ScreenMachine<CitySearchState, CitySearchAction, NoEvent> =
        screenMachine(
            id = DefinitionId(value = "city-search"),
            // v16 retains the debounce timer; Loading only owns the repository call.
            version = DefinitionVersion(value = "16"),
            initial = CitySearchState.Idle(),
        ) {
            state<CitySearchState.Idle> {
                on<CitySearchAction.ChangeQuery> {
                    target { action -> CitySearchState.QueryChanged(query = action.query, revision = state.revision + 1) }
                }
            }
            state<CitySearchState.QueryChanged> {
                always {
                    guard { state.query.isBlank() }
                    target { CitySearchState.Idle(query = state.query, revision = state.revision) }
                }
                always { target { CitySearchState.Debouncing(query = state.query, revision = state.revision) } }
            }
            state<CitySearchState.Debouncing> {
                on<CitySearchAction.ChangeQuery> {
                    target { action -> CitySearchState.QueryChanged(query = action.query, revision = state.revision + 1) }
                }
                after(350.milliseconds) { target { CitySearchState.Loading(query = state.query, revision = state.revision) } }
            }
            state<CitySearchState.Loading> {
                on<CitySearchAction.ChangeQuery> {
                    target { action -> CitySearchState.QueryChanged(query = action.query, revision = state.revision + 1) }
                }
                onEnter {
                    val result =
                        runCatching {
                            CitySearchAction.Found(state.revision, repository.search(state.query.trim()))
                        }.getOrElse { error ->
                            if (error is CancellationException || error !is Exception) throw error
                            CitySearchAction.Failed(state.revision, "Could not load cities. Try again.")
                        }
                    dispatch(result)
                }
                on<CitySearchAction.Found> {
                    guard { action -> action.revision == state.revision }
                    target { action -> CitySearchState.Success(state.query, state.revision, results = action.results) }
                }
                on<CitySearchAction.Failed> {
                    guard { action -> action.revision == state.revision }
                    target { action -> CitySearchState.Error(state.query, state.revision, cause = RequestFailure.Unavailable(message = action.message)) }
                }
            }
            state<CitySearchState.Success> {
                on<CitySearchAction.ChangeQuery> {
                    target { action -> CitySearchState.QueryChanged(query = action.query, revision = state.revision + 1) }
                }
            }
            state<CitySearchState.Error> {
                on<CitySearchAction.ChangeQuery> {
                    target { action -> CitySearchState.QueryChanged(query = action.query, revision = state.revision + 1) }
                }
            }
        }
}
