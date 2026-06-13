package koma.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StoreCancelLaunchTest {
    private val sharedLane = LaunchLane()
    private val otherLane = LaunchLane()
    private val missingLane = LaunchLane()

    sealed interface AppState : State {
        data object Active : AppState
    }

    sealed interface AppAction : Action {
        data class StartDropIfRunningShared(val marker: Int) : AppAction
        data class StartDropIfRunningOther(val marker: Int) : AppAction
        data class StartCancelPreviousShared(val marker: Int) : AppAction
        data class StartUntrackedShared(val marker: Int) : AppAction
        data object StartEnterLaunch : AppAction
        data object CancelShared : AppAction
        data object CancelMissing : AppAction
    }

    private fun createStore(
        testDispatcher: TestDispatcher,
        onStart: ((Int) -> Unit)? = null,
        onCancel: ((Int) -> Unit)? = null,
    ): Store<AppState, AppAction, Nothing> {
        return Store(AppState.Active) {
            coroutineContext(testDispatcher)

            state<AppState.Active> {
                enter {
                    launch(testDispatcher) {
                        onStart?.invoke(-1)
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onCancel?.invoke(-1)
                        }
                    }
                }

                action<AppAction.StartDropIfRunningShared> {
                    launch(
                        dispatcher = testDispatcher,
                        control = LaunchControl.DropIfRunning(sharedLane),
                    ) {
                        onStart?.invoke(action.marker)
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onCancel?.invoke(action.marker)
                        }
                    }
                }

                action<AppAction.StartDropIfRunningOther> {
                    launch(
                        dispatcher = testDispatcher,
                        control = LaunchControl.DropIfRunning(otherLane),
                    ) {
                        onStart?.invoke(action.marker)
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onCancel?.invoke(action.marker)
                        }
                    }
                }

                action<AppAction.StartCancelPreviousShared> {
                    launch(
                        dispatcher = testDispatcher,
                        control = LaunchControl.CancelPrevious(sharedLane),
                    ) {
                        onStart?.invoke(action.marker)
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onCancel?.invoke(action.marker)
                        }
                    }
                }

                action<AppAction.StartUntrackedShared> {
                    launch(
                        dispatcher = testDispatcher,
                        control = LaunchControl.Untracked,
                    ) {
                        onStart?.invoke(action.marker)
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            onCancel?.invoke(action.marker)
                        }
                    }
                }

                action<AppAction.StartEnterLaunch> {
                    // no-op: collecting state starts enter.launch for this runtime
                }

                action<AppAction.CancelShared> {
                    cancelLaunch(sharedLane)
                }

                action<AppAction.CancelMissing> {
                    cancelLaunch(missingLane)
                }
            }
        }
    }

    @Test
    fun cancelLaunch_cancelsDropIfRunningLaunchWithMatchingLane() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartDropIfRunningShared(marker = 1))
        runCurrent()

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(listOf(1), cancelled)
    }

    @Test
    fun cancelLaunch_cancelsCancelPreviousLaunchWithMatchingLane() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onStart = { started += it },
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartCancelPreviousShared(marker = 9))
        runCurrent()

        assertEquals(listOf(-1, 9), started)

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(listOf(9), cancelled)
    }

    @Test
    fun cancelLaunch_onlyAffectsMatchingTrackedKey() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartDropIfRunningShared(marker = 1))
        store.dispatch(AppAction.StartDropIfRunningOther(marker = 100))
        runCurrent()

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(listOf(1), cancelled)
    }

    @Test
    fun cancelLaunch_isNoOpWhenLaneDoesNotExist() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.CancelMissing)
        runCurrent()

        assertEquals(emptyList(), cancelled)
    }

    @Test
    fun cancelLaunch_affectsSharedTrackedLaneAcrossControls() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onStart = { started += it },
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartDropIfRunningShared(marker = 1))
        runCurrent()

        store.dispatch(AppAction.StartCancelPreviousShared(marker = 9))
        runCurrent()

        assertEquals(listOf(-1, 1, 9), started)
        assertEquals(listOf(1), cancelled)

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(listOf(1, 9), cancelled)
    }

    @Test
    fun cancelLaunch_doesNotCancelUntrackedLaunches() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartUntrackedShared(marker = 7))
        runCurrent()

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(emptyList(), cancelled)

        store.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun dispose_cancelsStoreLaunchesLikeClose() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartDropIfRunningShared(marker = 1))
        runCurrent()

        store.dispose()
        runCurrent()

        assertEquals(setOf(-1, 1), cancelled.toSet())
    }

    @Test
    fun cancelLaunch_doesNotCancelEnterLaunches() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val store = createStore(
            testDispatcher = testDispatcher,
            onStart = { started += it },
            onCancel = { cancelled += it },
        )

        store.dispatch(AppAction.StartEnterLaunch)
        runCurrent()

        assertEquals(listOf(-1), started)

        store.dispatch(AppAction.CancelShared)
        runCurrent()

        assertEquals(emptyList(), cancelled)

        store.close()
        runCurrent()

        assertEquals(listOf(-1), cancelled)
    }
}
