@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.data.OpenMeteoCitySearchRepository
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CitySearchTest {
    @Test
    fun changingCityCancelsHttpBeforeNextDebounceAndOnlyNewRequestCanFinish() =
        runTest {
            val calls = mutableListOf<String>()
            val cancelled = mutableListOf<String>()
            val kazanResponse = CompletableDeferred<Unit>()
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            val query = requireNotNull(request.url.parameters["name"])
                            calls += query
                            when (query) {
                                "Москва" -> {
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled += query
                                    }
                                }

                                "Казань" -> {
                                    kazanResponse.await()
                                    respond("""{"results":[{"name":"Казань","latitude":55.79,"longitude":49.12}]}""")
                                }

                                else -> {
                                    error("Unexpected query: $query")
                                }
                            }
                        }
                    }
                }
            val store = searchStore(OpenMeteoCitySearchRepository(client))
            try {
                store.start()
                store.dispatch(CitySearchAction.ChangeQuery("Москва"))
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(350)
                testScheduler.runCurrent()
                assertEquals(listOf("Москва"), calls)
                assertEquals(CitySearchState.Loading("Москва", 1), store.currentState.context)
                val oldRevision = store.currentState.context.revision

                store.dispatch(CitySearchAction.ChangeQuery("Казань"))
                testScheduler.runCurrent()
                // Cancellation reaches the HTTP engine immediately, before Kazan's debounce.
                assertEquals(listOf("Москва"), cancelled)
                assertTrue(store.currentState.commands.isEmpty())
                assertEquals(CitySearchState.Debouncing("Казань", 2), store.currentState.context)
                testScheduler.advanceTimeBy(349)
                testScheduler.runCurrent()
                assertEquals(listOf("Москва"), calls)
                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertEquals(listOf("Москва", "Казань"), calls)
                assertEquals(CitySearchState.Loading("Казань", 2), store.currentState.context)

                // Separately simulate a callback already queued outside the cancelled request.
                store.dispatch(CitySearchAction.Found(oldRevision, listOf(CityOption(City("Москва", 55.75, 37.62)))))
                testScheduler.runCurrent()
                assertEquals("Казань", store.currentState.context.query)
                assertEquals(CitySearchState.Loading("Казань", 2), store.currentState.context)
                kazanResponse.complete(Unit)
                testScheduler.runCurrent()
                assertEquals(
                    CitySearchState.Success("Казань", 2, listOf(CityOption(City("Казань", 55.79, 49.12)))),
                    store.currentState.context,
                )
            } finally {
                store.close()
                client.close()
            }
        }

    @Test
    fun statesRoundTripWithoutIndependentFlags() {
        val states =
            listOf(
                CitySearchState.Idle(" ", 1),
                CitySearchState.QueryChanged("P", 1),
                CitySearchState.Debouncing("P", 1),
                CitySearchState.Loading("P", 1),
                CitySearchState.Success("P", 1, listOf(CityOption(demoCities.first()))),
                CitySearchState.Success("P", 1, emptyList<CityOption>()),
                CitySearchState.Error("P", 1, cause = RequestFailure.Unavailable(message = "offline")),
                CitySearchState.Error("P", 1, cause = RequestFailure.Timeout),
            )
        for (state in states) {
            val encoded =
                kotlinx.serialization.json.Json
                    .encodeToString(CitySearchState.serializer(), state)
            assertEquals(
                state,
                kotlinx.serialization.json.Json
                    .decodeFromString(CitySearchState.serializer(), encoded),
            )
        }
    }

    @Test
    fun serverSearch_preservesCoordinatesAndDisambiguatesNames() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("geocoding-api.open-meteo.com", request.url.host)
                        assertEquals("/v1/search", request.url.encodedPath)
                        assertEquals("Paris", request.url.parameters["name"])
                        assertEquals("30", request.url.parameters["count"])
                        respond("""{"results":[{"name":"Paris","latitude":48.86,"longitude":2.35,"admin1":"Île-de-France","country":"France","id":2988507},{"name":"Paris","latitude":33.66,"longitude":-95.56,"admin1":"Texas","country":"United States"}]}""")
                    },
                )
            try {
                val cities = OpenMeteoCitySearchRepository(client).search(" Paris ")
                assertEquals(2, cities.size)
                assertEquals(City("Paris", 48.86, 2.35), cities.first().city)
                assertEquals("Texas, United States", cities.last().region)
            } finally {
                client.close()
            }
        }

    @Test
    fun serverSearch_handlesNoMatchesAndHttpErrors() =
        runTest {
            val empty = HttpClient(MockEngine { respond("{}") })
            val failed = HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) })
            try {
                assertEquals(emptyList(), OpenMeteoCitySearchRepository(empty).search("missing"))
                assertFailsWith<IllegalStateException> { OpenMeteoCitySearchRepository(failed).search("Paris") }
            } finally {
                empty.close()
                failed.close()
            }
        }

    @Test
    fun eachEditDebouncesAndClearCancelsTheRequest() =
        runTest {
            var calls = 0
            var cancelled = false
            val store =
                searchStore(
                    CitySearchRepository {
                        calls++
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                )
            try {
                store.start()
                store.dispatch(CitySearchAction.ChangeQuery("P"))
                testScheduler.runCurrent()
                val first = store.state.value
                assertEquals(CitySearchState.Debouncing("P", 1), first.context)
                testScheduler.advanceTimeBy(200)
                store.dispatch(CitySearchAction.ChangeQuery("Pa"))
                testScheduler.runCurrent()
                assertTrue(store.state.value.revision > first.revision)
                assertEquals("Pa", store.state.value.context.query)
                testScheduler.advanceTimeBy(349)
                testScheduler.runCurrent()
                assertEquals(0, calls)
                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertEquals(CitySearchState.Loading("Pa", 2), store.state.value.context)
                assertEquals(1, calls)
                store.dispatch(CitySearchAction.ChangeQuery(""))
                testScheduler.runCurrent()
                assertEquals(CitySearchState.Idle("", 3), store.state.value.context)
                assertTrue(cancelled)
                store.dispatch(CitySearchAction.Found(2, listOf(CityOption(demoCities.first()))))
                testScheduler.runCurrent()
                assertEquals(CitySearchState.Idle("", 3), store.state.value.context)
            } finally {
                store.close()
            }
        }

    @Test
    fun singleLetterFailureCanBeRetriedAndEmptyResultIsSuccess() =
        runTest {
            var calls = 0
            val store =
                searchStore(
                    CitySearchRepository { query ->
                        assertEquals("P", query)
                        if (++calls == 1) error("offline")
                        emptyList()
                    },
                )
            try {
                store.start()
                store.dispatch(CitySearchAction.ChangeQuery("P"))
                store.state.first { it.context is CitySearchState.Error }
                store.dispatch(CitySearchAction.ChangeQuery("P"))
                store.state.first { it.context is CitySearchState.Success }
                assertEquals(2, calls)
                assertEquals(CitySearchState.Success("P", 2, emptyList<CityOption>()), store.state.value.context)
            } finally {
                store.close()
            }
        }

    @Test
    fun timeoutCancelsRequestAndRejectsLateAnswer() =
        runTest {
            var cancelled = false
            val store =
                searchStore(
                    CitySearchRepository {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                )
            try {
                store.start()
                store.dispatch(CitySearchAction.ChangeQuery("P"))
                store.state.first { it.context == CitySearchState.Error("P", 1, cause = RequestFailure.Timeout) }
                testScheduler.runCurrent()
                assertTrue(cancelled)
                store.dispatch(CitySearchAction.Found(1, listOf(CityOption(demoCities.first()))))
                testScheduler.runCurrent()
                assertEquals(CitySearchState.Error("P", 1, cause = RequestFailure.Timeout), store.state.value.context)
            } finally {
                store.close()
            }
        }
}

private fun TestScope.searchStore(repository: CitySearchRepository) =
    MachineStore(
        CitySearchMachine.create(repository),
        CitySearchState.Idle(),
        backgroundScope,
        clock = MachineClock.monotonic(testScheduler.timeSource),
        coroutineContext = coroutineContext,
    )
