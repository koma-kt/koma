package actron.example.weather.feature.cities.presentation

import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandler

/**
 * Call the feature repository outside the pure decision and return a result through the Store queue.
 * The executor attaches the result to its command identity; commandHandler rethrows cancellation.
 * onFailure maps ordinary failures only. No handler writes screen state directly.
 */
fun citySearchHandler(
    repository: CitySearchRepository,
): CommandHandler<SearchCities, CitySearchAction> =
    commandHandler(
        onFailure = { request, _ -> CitySearchAction.Failed(request.revision, "Could not load cities. Try again.") },
    ) { request ->
        CitySearchAction.Found(request.revision, repository.search(request.query))
    }
