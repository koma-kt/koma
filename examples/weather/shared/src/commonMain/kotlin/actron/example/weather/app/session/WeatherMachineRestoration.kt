package actron.example.weather.app.session

import actron.decompose.MachineRestoration
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState

fun WeatherSession.searchRestoration(): MachineRestoration<CitySearchState, CitySearchAction, Unit, NoEvent> = MachineRestoration(clock, observers = { _, checkpoint -> listOf(observe(checkpoint, ::updateSearch)) }) { _, _ -> searchCheckpoint }
