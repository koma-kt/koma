package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreNextStateDeprecatedApiTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
    }

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedNextStateApis_delegateToNextStateBlockOverload() = runTest {
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)

            state<AppState.Loading> {
                enter {
                    nextState(AppState.Ready(count = 0))
                }
            }

            state<AppState.Ready> {
                action<AppAction.Increment> {
                    nextStateBy { state.copy(count = state.count + 1) }
                }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Increment)

        assertEquals(AppState.Ready(count = 1), store.currentState)
    }
}
