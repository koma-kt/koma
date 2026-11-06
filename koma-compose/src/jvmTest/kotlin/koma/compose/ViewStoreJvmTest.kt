package koma.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.LocalRetainedValuesStoreProvider
import androidx.compose.runtime.retain.ManagedRetainedValuesStore
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
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

        fun pumpFrame() {
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
    fun rememberViewStore_withStoreInstanceProvidesCurrentStateAndDispatchesAction() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>

        withComposition(
            content = {
                viewStore = rememberViewStore(store)
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
    fun rememberViewStore_invokesStoreLambdaOnlyOnceForSameKey() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        val recompositionTrigger = mutableStateOf(0)
        var storeCalls = 0
        var latestTriggerValue = -1
        lateinit var initialViewStore: ViewStore<UiState, UiAction, UiEvent>
        lateinit var latestViewStore: ViewStore<UiState, UiAction, UiEvent>

        withComposition(
            content = {
                latestTriggerValue = recompositionTrigger.value
                latestViewStore = rememberViewStore(key = "same") {
                    storeCalls++
                    store
                }
            },
            afterSetContent = {
                initialViewStore = latestViewStore
                assertEquals(1, storeCalls)
                assertEquals(0, latestTriggerValue)

                recompositionTrigger.value = 1
                Snapshot.sendApplyNotifications()
            },
        )

        assertEquals(1, latestTriggerValue)
        assertEquals(1, storeCalls)
        assertSame(initialViewStore, latestViewStore)
    }

    @Test
    fun rememberViewStore_recreatesWhenKeyChangesEvenIfStateIsEqual() = runTest(testDispatcher) {
        val firstStore = TestStore(UiState.Loading)
        val secondStore = TestStore(UiState.Loading)
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L
        var key = "first"
        lateinit var latestViewStore: ViewStore<UiState, UiAction, UiEvent>

        fun pumpFrame() {
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

        fun pumpFrame() {
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
    fun childThatDoesNotReadViewStoreState_doesNotUpdateRenderedNodeOnStateUpdate() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        val root = TestNode()
        var childCompositions = 0

        @Suppress("UNUSED_PARAMETER")
        @Composable
        fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
            childCompositions++
            StateNode(UiState.Ready(100))
        }

        withNodeComposition(
            root = root,
            content = {
                val viewStore = rememberViewStore { store }
                Child(viewStore)
            },
            afterSetContent = { pumpFrame ->
                assertEquals(UiState.Ready(100), root.singleChild.state)
                assertEquals(1, childCompositions)

                store.state.value = UiState.Ready(2)
                testScheduler.runCurrent()
                pumpFrame()

                assertEquals(UiState.Ready(100), root.singleChild.state)
                assertEquals(1, childCompositions)
            },
        )
    }

    @Test
    fun childThatReadsViewStoreState_doesNotRecomposeWhenStateIsEqual() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        val root = TestNode()
        var childCompositions = 0

        @Composable
        fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
            childCompositions++
            StateNode(viewStore.state)
        }

        withNodeComposition(
            root = root,
            content = {
                val viewStore = rememberViewStore { store }
                Child(viewStore)
            },
            afterSetContent = { pumpFrame ->
                assertEquals(UiState.Ready(1), root.singleChild.state)
                assertEquals(1, childCompositions)

                store.state.value = UiState.Ready(1)
                testScheduler.runCurrent()
                pumpFrame()

                assertEquals(UiState.Ready(1), root.singleChild.state)
                assertEquals(1, childCompositions)
            },
        )
    }

    @Test
    fun childThatReadsViewStoreState_updatesRenderedNodeOnStateUpdate() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(1))
        val root = TestNode()
        var childCompositions = 0

        @Composable
        fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
            childCompositions++
            StateNode(viewStore.state)
        }

        withNodeComposition(
            root = root,
            content = {
                val viewStore = rememberViewStore { store }
                Child(viewStore)
            },
            afterSetContent = { pumpFrame ->
                assertEquals(UiState.Ready(1), root.singleChild.state)
                assertEquals(1, childCompositions)

                store.state.value = UiState.Ready(2)
                testScheduler.runCurrent()
                pumpFrame()

                assertEquals(UiState.Ready(2), root.singleChild.state)
                assertEquals(2, childCompositions)
            },
        )
    }

    @Test
    fun rememberViewStore_closeBehaviorIsFixedPerRememberedStore() = runTest(testDispatcher) {
        val neverCloseStore = TestStore(UiState.Ready(0))
        val autoClose = mutableStateOf(false)

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

        fun pumpFrame() {
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
                    nextState { UiState.Ready(0) }
                }
            }
            state<UiState.Ready> {
                action<UiAction.Increment> {
                    nextState { state.copy(value = state.value + 1) }
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
                    nextState { state.copy(value = state.value + 1) }
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
                    nextState { UiState.Ready(0) }
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

    @Test
    fun retainViewStore_reusesStoreAcrossCompositionsAndClosesOnlyWhenRetired() = runTest(testDispatcher) {
        val retainedValues = ManagedRetainedValuesStore()
        val store = TestStore(UiState.Ready(1))
        var creations = 0
        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>
        val content: @Composable () -> Unit = {
            LocalRetainedValuesStoreProvider(retainedValues) {
                viewStore = retainViewStore {
                    creations++
                    store
                }
            }
        }
        try {
            withComposition(content) {
                assertEquals(UiState.Ready(1), viewStore.state)
                viewStore.dispatch(UiAction.Increment)
                assertEquals(listOf<UiAction>(UiAction.Increment), store.dispatchedActions)
            }
            assertEquals(0, store.closeCount)
            val firstViewStore = viewStore
            store.state.value = UiState.Ready(2)

            withComposition(content) {
                assertEquals(1, creations)
                assertNotSame(firstViewStore, viewStore)
                assertEquals(UiState.Ready(2), viewStore.state)
                store.state.value = UiState.Ready(3)
            }
            assertEquals(UiState.Ready(3), viewStore.state)
            assertEquals(0, store.closeCount)
        } finally {
            retainedValues.dispose()
        }
        assertEquals(1, store.closeCount)
    }

    @Test
    fun retainViewStore_recreatesStoreWhenEitherKeyChanges() = runTest(testDispatcher) {
        val firstKey = mutableStateOf("account-1")
        val secondKey = mutableStateOf(1)
        val stores = mutableListOf<TestStore>()
        lateinit var viewStore: ViewStore<UiState, UiAction, UiEvent>
        withNodeComposition(
            root = TestNode(),
            content = {
                viewStore = retainViewStore(firstKey.value, secondKey.value) {
                    TestStore(UiState.Ready(secondKey.value)).also { stores += it }
                }
            },
            afterSetContent = { pumpFrame ->
                assertEquals(1, stores.size)
                val initialViewStore = viewStore
                secondKey.value = 2
                pumpFrame()
                assertEquals(2, stores.size)
                assertEquals(1, stores[0].closeCount)
                assertNotSame(initialViewStore, viewStore)
                assertEquals(UiState.Ready(2), viewStore.state)

                firstKey.value = "account-2"
                pumpFrame()
                assertEquals(3, stores.size)
                assertEquals(1, stores[1].closeCount)
                assertEquals(0, stores[2].closeCount)
            },
        )
        assertTrue(stores.all { it.closeCount == 1 })
    }

    @Test
    fun retainViewStore_withoutRetainingOwnerClosesOnDisposal() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(0))
        withComposition(content = { retainViewStore { store } })
        assertEquals(1, store.closeCount)
    }

    @Test
    fun rememberViewStore_externalStoreRemainsOpenAfterDisposal() = runTest(testDispatcher) {
        val store = TestStore(UiState.Ready(0))
        withComposition(content = { rememberViewStore(store) })
        assertEquals(0, store.closeCount)
    }

    @Test
    fun rememberViewStore_autoCloseControlsCleanup() = runTest(testDispatcher) {
        for (autoClose in listOf(false, true)) {
            val store = TestStore(UiState.Ready(0))
            withComposition(content = { rememberViewStore(store, autoClose = autoClose) })
            assertEquals(if (autoClose) 1 else 0, store.closeCount)
        }
    }

    private suspend fun TestScope.withComposition(
        content: @Composable () -> Unit,
        afterSetContent: suspend () -> Unit = {},
    ) {
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L

        fun pumpFrame() {
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

    private suspend fun TestScope.withNodeComposition(
        root: TestNode,
        content: @Composable () -> Unit,
        afterSetContent: suspend (pumpFrame: suspend () -> Unit) -> Unit = {},
    ) {
        val frameClock = BroadcastFrameClock()
        var frameTimeNanos = 0L

        suspend fun pumpFrame() {
            testScheduler.runCurrent()
            Snapshot.sendApplyNotifications()
            frameTimeNanos += 16_000_000L
            frameClock.sendFrame(frameTimeNanos)
            testScheduler.runCurrent()
        }

        withContext(frameClock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(TestApplier(root), recomposer)
                try {
                    composition.setContent(content)
                    repeat(2) { pumpFrame() }

                    afterSetContent(::pumpFrame)
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

@Composable
private fun StateNode(state: UiState) {
    ComposeNode<TestNode, TestApplier>(
        factory = { TestNode() },
        update = {
            set(state) {
                this.state = it
            }
        },
    )
}

private class TestNode {
    val children = mutableListOf<TestNode>()
    var state: UiState? = null

    val singleChild: TestNode
        get() = children.single()
}

private class TestApplier(root: TestNode) : AbstractApplier<TestNode>(root) {
    override fun insertTopDown(index: Int, instance: TestNode) = Unit

    override fun insertBottomUp(index: Int, instance: TestNode) {
        current.children.add(index, instance)
    }

    override fun move(from: Int, to: Int, count: Int) {
        val children = current.children
        val moved = children.subList(from, from + count).toList()
        repeat(count) {
            children.removeAt(from)
        }
        children.addAll(if (to > from) to - count else to, moved)
    }

    override fun remove(index: Int, count: Int) {
        repeat(count) {
            current.children.removeAt(index)
        }
    }

    override fun onClear() {
        root.children.clear()
    }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun onClear() = Unit
}
