package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StorePluginExecutionPolicyTest {

    data class AppState(val count: Int = 0) : State

    sealed interface AppAction : Action {
        data object Increment : AppAction
    }

    private fun createStore(
        testDispatcher: TestDispatcher,
        records: MutableList<String>,
        firstPluginStarted: CompletableDeferred<Unit>,
        secondPluginStarted: CompletableDeferred<Unit>,
        firstPluginCanFinish: CompletableDeferred<Unit>,
        setupPolicy: PluginExecutionPolicy = PluginExecutionPolicy.Concurrent,
        patchedPolicy: PluginExecutionPolicy? = null,
    ): Store<AppState, AppAction, Nothing> {
        val store = Store<AppState, AppAction, Nothing>(initialState = AppState()) {
            coroutineContext(testDispatcher)
            pluginExecutionPolicy(setupPolicy)

            plugin(
                Plugin(
                    onAction = { _, _ ->
                        records += "first:start"
                        firstPluginStarted.complete(Unit)
                        firstPluginCanFinish.await()
                        records += "first:end"
                    },
                ),
                Plugin(
                    onAction = { _, _ ->
                        records += "second:start"
                        secondPluginStarted.complete(Unit)
                        records += "second:end"
                    },
                ),
            )

            state<AppState> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }
        if (patchedPolicy != null) {
            store.patchForTest {
                pluginExecutionPolicy(patchedPolicy)
            }
        }
        return store
    }

    @Test
    fun concurrent_isDefaultAndAllowsOtherPluginsToRunWhileOneIsSuspended() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val records = mutableListOf<String>()
        val firstPluginStarted = CompletableDeferred<Unit>()
        val secondPluginStarted = CompletableDeferred<Unit>()
        val firstPluginCanFinish = CompletableDeferred<Unit>()
        val store = createStore(
            testDispatcher = testDispatcher,
            records = records,
            firstPluginStarted = firstPluginStarted,
            secondPluginStarted = secondPluginStarted,
            firstPluginCanFinish = firstPluginCanFinish,
        )

        store.dispatch(AppAction.Increment)
        runCurrent()

        assertTrue(firstPluginStarted.isCompleted)
        assertTrue(secondPluginStarted.isCompleted)
        assertEquals(
            listOf("first:start", "second:start", "second:end"),
            records,
        )

        firstPluginCanFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("first:start", "second:start", "second:end", "first:end"),
            records,
        )
        assertEquals(AppState(count = 1), store.currentState)
    }

    @Test
    fun inRegistrationOrder_waitsForPreviousPluginToFinishBeforeStartingNextOne() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val records = mutableListOf<String>()
        val firstPluginStarted = CompletableDeferred<Unit>()
        val secondPluginStarted = CompletableDeferred<Unit>()
        val firstPluginCanFinish = CompletableDeferred<Unit>()
        val store = createStore(
            testDispatcher = testDispatcher,
            records = records,
            firstPluginStarted = firstPluginStarted,
            secondPluginStarted = secondPluginStarted,
            firstPluginCanFinish = firstPluginCanFinish,
            setupPolicy = PluginExecutionPolicy.InRegistrationOrder,
        )

        store.dispatch(AppAction.Increment)
        runCurrent()

        assertTrue(firstPluginStarted.isCompleted)
        assertFalse(secondPluginStarted.isCompleted)
        assertEquals(listOf("first:start"), records)

        firstPluginCanFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("first:start", "first:end", "second:start", "second:end"),
            records,
        )
        assertEquals(AppState(count = 1), store.currentState)
    }

    @Test
    fun patch_shouldOverridePluginExecutionPolicy() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val records = mutableListOf<String>()
        val firstPluginStarted = CompletableDeferred<Unit>()
        val secondPluginStarted = CompletableDeferred<Unit>()
        val firstPluginCanFinish = CompletableDeferred<Unit>()
        val store = createStore(
            testDispatcher = testDispatcher,
            records = records,
            firstPluginStarted = firstPluginStarted,
            secondPluginStarted = secondPluginStarted,
            firstPluginCanFinish = firstPluginCanFinish,
            setupPolicy = PluginExecutionPolicy.Concurrent,
            patchedPolicy = PluginExecutionPolicy.InRegistrationOrder,
        )

        store.dispatch(AppAction.Increment)
        runCurrent()

        assertTrue(firstPluginStarted.isCompleted)
        assertFalse(secondPluginStarted.isCompleted)
        assertEquals(listOf("first:start"), records)

        firstPluginCanFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("first:start", "first:end", "second:start", "second:end"),
            records,
        )
        assertEquals(AppState(count = 1), store.currentState)
    }
}
