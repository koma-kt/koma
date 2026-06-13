package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌────────────────────────────────────────┐
// │                                        │
// │  Initial ────► Loading ─────► Success  │
// │    ▲  │             │                  │
// │    │  └──────────┐  │                  │
// │    │             │  │                  │
// │    │             ▼  ▼                  │
// │    └─────────── Error ◄──── Any State  │
// │                                        │
// └────────────────────────────────────────┘
//
// Initial: Username/password input screen
// Loading: Login processing
// Success: Login successful
// Error: Login failed (validation or authentication error)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoginUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Login repository interface
    interface LoginRepository {
        suspend fun login(username: String, password: String): Boolean
    }

    // Mock repository implementation
    class MockLoginRepository(var shouldSucceed: Boolean) : LoginRepository {
        override suspend fun login(username: String, password: String): Boolean {
            return shouldSucceed
        }
    }

    // State definitions
    sealed interface AppState : State {
        data object Initial : AppState
        data class Loading(val username: String, val password: String) : AppState
        data class Success(val username: String) : AppState
        data class Error(val message: String) : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data class Login(val username: String, val password: String) : AppAction
        data object RetryFromError : AppAction
    }

    // Event definitions
    sealed interface AppEvent : Event {
        data class NavigateToHome(val username: String) : AppEvent
    }

    // Create a Store using the standard Store factory function
    private fun createTestStore(
        repository: LoginRepository,
    ): Store<AppState, AppAction, AppEvent> {
        return Store(AppState.Initial) {
            coroutineContext(Dispatchers.Unconfined)
            // Processing for Initial state
            state<AppState.Initial> {
                action<AppAction.Login> {
                    // Validate input values
                    val isValidInput = action.username.isNotBlank() && action.password.isNotBlank()

                    nextState {
                        if (isValidInput) {
                            // For valid input, transition to loading state
                            AppState.Loading(action.username, action.password)
                        } else {
                            // For invalid input, transition to error state
                            AppState.Error("Username and password must not be empty")
                        }
                    }
                }
            }
            // Processing for Loading state
            state<AppState.Loading> {
                enter {
                    // Execute login process in repository
                    val loginSuccessful = repository.login(state.username, state.password)

                    if (loginSuccessful) {
                        // Process for successful login
                        event(AppEvent.NavigateToHome(state.username))

                        nextState {
                            // Transition to success state
                            AppState.Success(state.username)
                        }
                    } else {
                        // Process for failed login
                        nextState {
                            // Transition to error state
                            AppState.Error("Authentication failed")
                        }
                    }
                }
            }
            // Processing for Error state
            state<AppState.Error> {
                action<AppAction.RetryFromError> {
                    nextState { AppState.Initial }
                }
            }
            // Error handling for all states
            state<AppState> {
                recover<Exception> {
                    nextState { AppState.Error(error.message ?: "Unknown error") }
                }
            }
        }
    }

    @Test
    fun loginFlow_successCase() = runTest(testDispatcher) {
        // Create Store with a repository that will succeed
        val repository = MockLoginRepository(shouldSucceed = true)
        val store = createTestStore(repository)

        // Verify initial state
        assertIs<AppState.Initial>(store.currentState)

        // Start login process
        store.dispatch(AppAction.Login("user123", "password123"))

        // Login successful, should be in Success state
        assertIs<AppState.Success>(store.currentState)
        val successState = store.currentState as AppState.Success
        assertEquals("user123", successState.username)
    }

    @Test
    fun loginFlow_failureCase() = runTest(testDispatcher) {
        // Create Store with a repository that will fail
        val repository = MockLoginRepository(shouldSucceed = false)
        val store = createTestStore(repository)

        // Verify initial state
        assertIs<AppState.Initial>(store.currentState)

        // Start login process
        store.dispatch(AppAction.Login("user123", "wrong_password"))

        // Login fails, should be in Error state
        assertIs<AppState.Error>(store.currentState)
        val errorState = store.currentState as AppState.Error
        assertEquals("Authentication failed", errorState.message)
    }

    @Test
    fun errorState_canRetryLogin() = runTest(testDispatcher) {
        // Create Store with a repository that will initially fail
        val repository = MockLoginRepository(shouldSucceed = false)
        val store = createTestStore(repository)

        // Verify initial state
        assertIs<AppState.Initial>(store.currentState)

        // First login attempt (will fail)
        store.dispatch(AppAction.Login("user123", "wrong_password"))

        // Login fails, should be in Error state
        assertIs<AppState.Error>(store.currentState)

        // Change repository to succeed on next attempt
        repository.shouldSucceed = true

        // Send retry action
        store.dispatch(AppAction.RetryFromError)
        assertIs<AppState.Initial>(store.currentState)

        // Try logging in again
        store.dispatch(AppAction.Login("user123", "correct_password"))

        // Login process is automatically handled by the enter block
        assertIs<AppState.Success>(store.currentState)
    }
}
