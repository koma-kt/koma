package koma.compose

import androidx.compose.runtime.mutableStateOf
import koma.core.State
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewStoreTest {
    @Test
    fun viewStore_readsLatestValueFromStateRef() {
        val stateRef = mutableStateOf(CounterState(10))
        val viewStore = ViewStore<CounterState, Nothing, Nothing>(stateRef = stateRef, {}, emptyFlow())

        assertEquals(CounterState(10), viewStore.state)

        stateRef.value = CounterState(20)

        assertEquals(CounterState(20), viewStore.state)
    }
}

private data class CounterState(val count: Int) : State
