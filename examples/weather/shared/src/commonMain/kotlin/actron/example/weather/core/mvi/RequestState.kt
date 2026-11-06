package actron.example.weather.core.mvi

import kotlinx.serialization.Serializable

/** Shared request lifecycle. Each machine declares only the states it uses. */
@Serializable
sealed interface RequestState<out T : Any> {
    @Serializable
    data object Idle : RequestState<Nothing>

    @Serializable
    data object Ready : RequestState<Nothing>

    @Serializable
    data object Loading : RequestState<Nothing>

    @Serializable
    data class Success<T : Any>(
        val value: T,
    ) : RequestState<T>

    @Serializable
    data class Error(
        val cause: RequestFailure,
    ) : RequestState<Nothing>
}

/** The reason is data inside Error; retry behavior belongs to the single error state. */
@Serializable
sealed interface RequestFailure {
    @Serializable
    data class Unavailable(
        val message: String,
    ) : RequestFailure
}
