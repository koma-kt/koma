package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestFailure
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.WeatherContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestStateTest {
    @Test
    fun parallelResultsRoundTripWithTheirOwnPayloadTypes() {
        val city = demoCities.first()
        val weather = WeatherReading(city, 20.0, 19.0, 12.0, 0, "12:00")
        val forecast = HourlyForecast(city, listOf(ForecastHour("13:00", 21.0, 10.0, 5)))
        val states =
            listOf(
                WeatherContext(city = city, current = RequestState.Success(value = weather), hourly = RequestState.Success(value = forecast)),
                WeatherContext(city = city, current = RequestState.Success(value = weather), hourly = RequestState.Error(cause = RequestFailure.Unavailable(message = "offline"))),
                WeatherContext(city = city, current = RequestState.Loading, hourly = RequestState.Error(cause = RequestFailure.Unavailable("offline"))),
                WeatherContext(city),
            )
        for (state in states) {
            val encoded = Json.encodeToString(WeatherContext.serializer(), state)
            assertEquals(state, Json.decodeFromString(WeatherContext.serializer(), encoded))
        }
    }

    @Test
    fun generatedSerializerRoundTripsEveryVariantAndRequiresPayloads() {
        val serializer = RequestState.serializer(String.serializer())
        val states =
            listOf(
                RequestState.Idle,
                RequestState.Ready,
                RequestState.Loading,
                RequestState.Success("answer"),
                RequestState.Error(cause = RequestFailure.Unavailable(message = "offline")),
                RequestState.Error(cause = RequestFailure.Unavailable("offline")),
            )
        for (state in states) {
            val encoded = Json.encodeToJsonElement(serializer, state).jsonObject
            assertEquals(state, Json.decodeFromJsonElement(serializer, encoded))
            if (state is RequestState.Success || state is RequestState.Error) {
                val missingPayload = JsonObject(encoded.filterKeys { it != "value" && it != "cause" })
                assertFailsWith<SerializationException> { Json.decodeFromJsonElement(serializer, missingPayload) }
            }
        }
    }
}
