package io.github.komakt.koma.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withRunningRecomposer
import io.github.komakt.koma.core.Action
import io.github.komakt.koma.core.Event
import io.github.komakt.koma.core.State
import io.github.komakt.koma.core.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ViewStoreJvmTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun stateContent_callsBlockOnlyForMatchingState() = runTest(testDispatcher) {
        val renderedValues = mutableListOf<Int>()

        withComposition(
            content = {
                ViewStore<UiState, Nothing, Nothing>(state = UiState.Ready(10))
                    .stateContent<UiState.Ready> {
                        renderedValues += state.value
                    }

                ViewStore<UiState, Nothing, Nothing>(state = UiState.Loading)
                    .stateContent<UiState.Ready> {
                        renderedValues += state.value
                    }
            },
        )

        assertEquals(listOf(10), renderedValues)
    }

    @Test
    fun eventEffect_collectsOnlySpecifiedEventType() = runTest(testDispatcher) {
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
        val handled = mutableListOf<UiEvent.ValueChanged>()

        withComposition(
            content = {
                ViewStore<UiState, UiAction, UiEvent>(
                    state = UiState.Ready(0),
                    eventFlow = events,
                ).eventEffect<UiEvent.ValueChanged> { event ->
                    handled += event
                }
            },
            afterSetContent = {
                assertTrue(events.tryEmit(UiEvent.Reset))
                assertTrue(events.tryEmit(UiEvent.ValueChanged(42)))
            },
        )

        assertEquals(listOf(UiEvent.ValueChanged(42)), handled)
    }

    @Test
    fun eventEffect_usesLatestViewStoreAndLambdaAfterRecomposition() = runTest(testDispatcher) {
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
        val handled = mutableListOf<String>()
        var label = "initial"
        var viewState: UiState = UiState.Ready(1)
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NoOpApplier(), recomposer)
                try {
                    composition.setContent {
                        ViewStore<UiState, UiAction, UiEvent>(
                            state = viewState,
                            eventFlow = events,
                        ).eventEffect<UiEvent.ValueChanged> { event ->
                            handled += "${(state as UiState.Ready).value}:$label:${event.value}"
                        }
                    }
                    repeat(2) { pumpFrame() }

                    label = "updated"
                    viewState = UiState.Ready(2)
                    composition.setContent {
                        ViewStore<UiState, UiAction, UiEvent>(
                            state = viewState,
                            eventFlow = events,
                        ).eventEffect<UiEvent.ValueChanged> { event ->
                            handled += "${(state as UiState.Ready).value}:$label:${event.value}"
                        }
                    }
                    repeat(2) { pumpFrame() }

                    assertTrue(events.tryEmit(UiEvent.ValueChanged(42)))
                    repeat(2) { pumpFrame() }
                } finally {
                    composition.dispose()
                    pumpFrame()
                }
            }
        }

        assertEquals(listOf("2:updated:42"), handled)
    }

    @Suppress("DEPRECATION")
    @Test
    fun render_delegatesToStateContent() = runTest(testDispatcher) {
        val renderedValues = mutableListOf<Int>()

        withComposition(
            content = {
                ViewStore<UiState, Nothing, Nothing>(state = UiState.Ready(7))
                    .render<UiState.Ready> {
                        renderedValues += state.value
                    }
            },
        )

        assertEquals(listOf(7), renderedValues)
    }

    @Suppress("DEPRECATION")
    @Test
    fun handle_delegatesToEventEffect() = runTest(testDispatcher) {
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
        val handled = mutableListOf<UiEvent.ValueChanged>()

        withComposition(
            content = {
                ViewStore<UiState, UiAction, UiEvent>(
                    state = UiState.Ready(0),
                    eventFlow = events,
                ).handle<UiEvent.ValueChanged> { event ->
                    handled += event
                }
            },
            afterSetContent = {
                assertTrue(events.tryEmit(UiEvent.ValueChanged(100)))
            },
        )

        assertEquals(listOf(UiEvent.ValueChanged(100)), handled)
    }

    @Test
    fun rememberViewStore_providesCurrentStateAndDispatchesAction() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>

        withComposition(
            content = {
                viewStore = rememberViewStore { store }
            },
            afterSetContent = {
                assertEquals(UiState.Ready(1), viewStore.state)

                viewStore.dispatch(UiAction.Increment)
                assertEquals(listOf<UiAction>(UiAction.Increment), store.dispatchedActions)
            },
        )
    }

    @Test
    fun rememberViewStore_keepsSameInstanceWhileStateUpdates() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>

        withComposition(
            content = {
                viewStore = rememberViewStore { store }
            },
            afterSetContent = {
                val initialViewStore = viewStore
                store.state.value = UiState.Ready(2)
                testScheduler.runCurrent()

                assertSame(initialViewStore, viewStore)
                assertEquals(UiState.Ready(2), viewStore.state)
            },
        )
    }

    @Test
    fun rememberViewStore_recreatesWhenKeyChangesEvenIfStateIsEqual() = runTest(testDispatcher) {
        val firstStore = TestStore(UiState.Loading)
        val secondStore = TestStore(UiState.Loading)
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L
        var key = "first"
        lateinit var latestViewStore: ViewStore<UiState, UiAction, UiEvent>

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NoOpApplier(), recomposer)
                try {
                    composition.setContent {
                        latestViewStore = rememberViewStore(key = key) {
                            if (key == "first") firstStore else secondStore
                        }
                    }
                    repeat(2) { pumpFrame() }
                    val initialViewStore = latestViewStore

                    key = "second"
                    composition.setContent {
                        latestViewStore = rememberViewStore(key = key) {
                            if (key == "first") firstStore else secondStore
                        }
                    }
                    repeat(2) { pumpFrame() }

                    assertNotSame(initialViewStore, latestViewStore)

                    latestViewStore.dispatch(UiAction.Increment)

                    assertTrue(firstStore.dispatchedActions.isEmpty())
                    assertEquals(listOf<UiAction>(UiAction.Increment), secondStore.dispatchedActions)
                } finally {
                    composition.dispose()
                    pumpFrame()
                }
            }
        }
    }

    @Test
    fun rememberViewStore_keyChangeUsesNewStoreStateOnFirstComposition() = runTest(testDispatcher) {
        val firstStore = TestStore(UiState.Ready(1))
        val secondStore = TestStore(UiState.Ready(2))
        val renderedStates = mutableListOf<UiState>()
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L
        var key = "first"

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NoOpApplier(), recomposer)
                try {
                    composition.setContent {
                        renderedStates += rememberViewStore(key = key) {
                            if (key == "first") firstStore else secondStore
                        }.state
                    }
                    repeat(2) { pumpFrame() }

                    renderedStates.clear()
                    key = "second"
                    composition.setContent {
                        renderedStates += rememberViewStore(key = key) {
                            if (key == "first") firstStore else secondStore
                        }.state
                    }
                    repeat(2) { pumpFrame() }

                    assertEquals(UiState.Ready(2), renderedStates.first())
                } finally {
                    composition.dispose()
                    pumpFrame()
                }
            }
        }
    }

    @Test
    fun rememberViewStore_capturedCallbackReadsLatestState() = runTest(testDispatcher) {
        val store = TestStore(UiState.Loading)
        lateinit var canHide: () -> Boolean

        withComposition(
            content = {
                val viewStore = rememberViewStore { store }
                canHide = remember {
                    {
                        viewStore.state !is UiState.Busy
                    }
                }
            },
            afterSetContent = {
                assertTrue(canHide())
                store.state.value = UiState.Busy
            },
        )

        assertFalse(canHide())
    }

    @Test
    fun childThatDoesNotReadState_isSkippedOnStateUpdate() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        var childCompositions = 0

        @Composable
        fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
            childCompositions++
        }

        withComposition(
            content = {
                val viewStore = rememberViewStore { store }
                Child(viewStore)
            },
            afterSetContent = {
                assertEquals(1, childCompositions)

                store.state.value = UiState.Ready(2)
                testScheduler.runCurrent()
            },
        )

        assertEquals(1, childCompositions)
    }

    @Test
    fun childThatReadsViewStoreState_isStillSkippedOnStateUpdate() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        var childCompositions = 0

        @Composable
        fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
            viewStore.state
            childCompositions++
        }

        withComposition(
            content = {
                val viewStore = rememberViewStore { store }
                Child(viewStore)
            },
            afterSetContent = {
                assertEquals(1, childCompositions)

                store.state.value = UiState.Ready(2)
                testScheduler.runCurrent()
            },
        )

        assertEquals(1, childCompositions)
    }

    @Test
    fun rememberViewStore_closeBehaviorIsFixedPerRememberedStore() = runTest(testDispatcher) {
        val neverCloseStore = TestStore(UiState.Ready(0))
        var autoClose = mutableStateOf(false)

        withComposition(
            content = {
                rememberViewStore(autoClose = autoClose.value) { neverCloseStore }
            },
            afterSetContent = {
                autoClose.value = true
            },
        )

        assertEquals(0, neverCloseStore.closeCount)

        val alwaysCloseStore = TestStore(UiState.Ready(0))
        autoClose.value = true
        withComposition(
            content = {
                rememberViewStore(autoClose = autoClose.value) { alwaysCloseStore }
            },
            afterSetContent = {
                autoClose.value = false
            },
        )

        assertEquals(1, alwaysCloseStore.closeCount)
    }

    @Test
    fun rememberViewStore_keyChangeAppliesAutoCloseToNewRememberedStore() = runTest(testDispatcher) {
        val firstStore = TestStore(UiState.Ready(0))
        val secondStore = TestStore(UiState.Ready(0))
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L
        var key = "first"
        var autoClose = false

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NoOpApplier(), recomposer)
                try {
                    composition.setContent {
                        rememberViewStore(key = key, autoClose = autoClose) {
                            if (key == "first") firstStore else secondStore
                        }
                    }
                    repeat(2) { pumpFrame() }

                    autoClose = true
                    key = "second"
                    composition.setContent {
                        rememberViewStore(key = key, autoClose = autoClose) {
                            if (key == "first") firstStore else secondStore
                        }
                    }
                    repeat(2) { pumpFrame() }

                    assertEquals(0, firstStore.closeCount)
                    assertEquals(0, secondStore.closeCount)
                } finally {
                    composition.dispose()
                    pumpFrame()
                }
            }
        }

        assertEquals(0, firstStore.closeCount)
        assertEquals(1, secondStore.closeCount)
    }

    @Test
    fun rememberViewStore_withRealStore_reflectsStateTransitionAndDispatch() = runTest(testDispatcher) {
        val store = Store<UiState, UiAction, UiEvent>(initialState = UiState.Loading) {
            coroutineContext(coroutineContext)
            state<UiState.Loading> {
                enter {
                    nextState(UiState.Ready(0))
                }
            }
            state<UiState.Ready> {
                action<UiAction.Increment> {
                    nextState(state.copy(value = state.value + 1))
                }
            }
        }

        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>
        withComposition(
            content = {
                viewStore = rememberViewStore { store }
            },
            afterSetContent = {
                assertEquals(UiState.Ready(0), viewStore.state)
                viewStore.dispatch(UiAction.Increment)
            },
        )

        assertEquals(UiState.Ready(1), store.currentState)
        store.close()
    }

    @Test
    fun rememberViewStore_withRealStore_autoCloseStopsFurtherDispatch() = runTest(testDispatcher) {
        val store = Store<UiState, UiAction, UiEvent>(initialState = UiState.Ready(0)) {
            coroutineContext(coroutineContext)
            state<UiState.Ready> {
                action<UiAction.Increment> {
                    nextState(state.copy(value = state.value + 1))
                }
            }
        }

        withComposition(
            content = {
                rememberViewStore(autoClose = true) { store }
            },
            afterSetContent = {
                store.dispatch(UiAction.Increment)
            },
        )

        assertEquals(UiState.Ready(1), store.currentState)

        store.dispatch(UiAction.Increment)
        testScheduler.runCurrent()

        assertEquals(UiState.Ready(1), store.currentState)
    }

    @Test
    fun rememberViewStore_usesStateFlowValueForInitialRenderingBeforeFirstCollectEmission() = runTest(testDispatcher) {
        val enterGate = CompletableDeferred<Unit>()
        val store = Store<UiState, UiAction, UiEvent>(initialState = UiState.Loading) {
            coroutineContext(coroutineContext)
            state<UiState.Loading> {
                enter(dispatcher = testDispatcher) {
                    enterGate.await()
                    nextState(UiState.Ready(0))
                }
            }
        }

        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>

        withComposition(
            content = {
                viewStore = rememberViewStore(autoClose = true) { store }
            },
            afterSetContent = {
                assertEquals(UiState.Loading, viewStore.state)
                assertEquals(UiState.Loading, store.currentState)
                assertTrue(enterGate.complete(Unit))
                testScheduler.runCurrent()
                assertEquals(UiState.Ready(0), store.currentState)
            },
        )
    }

    private suspend fun TestScope.withComposition(
        content: @Composable () -> Unit,
        afterSetContent: suspend () -> Unit = {},
    ) {
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NoOpApplier(), recomposer)
                try {
                    composition.setContent(content)
                    repeat(2) { pumpFrame() }

                    afterSetContent()
                    repeat(2) { pumpFrame() }
                } finally {
                    composition.dispose()
                    pumpFrame()
                }
            }
        }
    }
}

private sealed interface UiState : State {
    data object Loading : UiState
    data object Busy : UiState
    data class Ready(val value: Int) : UiState
}

private sealed interface UiAction : Action {
    data object Increment : UiAction
}

private sealed interface UiEvent : Event {
    data object Reset : UiEvent
    data class ValueChanged(val value: Int) : UiEvent
}

private class TestStore(
    initialState: UiState,
) : Store<UiState, UiAction, UiEvent> {
    override val state = MutableStateFlow(initialState)
    private val eventFlow = MutableSharedFlow<UiEvent>()
    override val event: Flow<UiEvent> = eventFlow
    override val currentState: UiState get() = state.value

    val dispatchedActions = mutableListOf<UiAction>()
    var closeCount: Int = 0
        private set

    override fun start() = Unit

    override fun dispatch(action: UiAction) {
        dispatchedActions += action
    }

    override fun collectState(state: (UiState) -> Unit) = Unit

    override fun collectEvent(event: (UiEvent) -> Unit) = Unit

    override fun close() {
        closeCount++
    }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun onClear() = Unit
}
