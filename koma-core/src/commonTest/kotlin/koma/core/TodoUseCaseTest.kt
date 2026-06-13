package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌───────────────────────────────────────────────┐
// │                                               │
// │  Idle ────► Loading ────► Loaded ───────┐     │
// │   ▲                          ▲          │     │
// │   │                          │          │     │
// │   │                          │          ▼     │
// │  Error ◄──── Any State       │       Editing  │
// │                              │          │     │
// │                              └──────────┘     │
// │                                               │
// └───────────────────────────────────────────────┘
//
// Idle: Initial state, no todos loaded yet
// Loading: Fetching todos
// Loaded: Todos successfully loaded, showing list
// Error: Error occurred during loading or operation
// Editing: Editing a todo item

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodoUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Todo item data class
    data class Todo(
        val id: String,
        val title: String,
        val completed: Boolean = false,
    )

    // Simple ID counter for tests
    private var idCounter = 0

    // Helper function to generate IDs in a platform-independent way
    private fun generateId(): String {
        val random = Random.nextInt(10000, 99999)
        idCounter++
        return "${abs(random)}_${idCounter}"
    }

    // Repository interface
    interface TodoRepository {
        suspend fun loadTodos(): List<Todo>
        suspend fun addTodo(todo: Todo): Todo
        suspend fun updateTodo(todo: Todo): Todo
        suspend fun deleteTodo(todoId: String): Boolean
    }

    // Mock repository implementation
    class MockTodoRepository(var shouldSucceed: Boolean) : TodoRepository {
        val initialTodos = listOf(
            Todo("1", "Complete project", false),
            Todo("2", "Write tests", true),
            Todo("3", "Review code", false),
        )

        private val _todos = initialTodos.toMutableList()

        override suspend fun loadTodos(): List<Todo> {
            if (!shouldSucceed) throw Exception("Failed to load todos")
            return _todos.toList()
        }

        override suspend fun addTodo(todo: Todo): Todo {
            if (!shouldSucceed) throw Exception("Failed to add todo")
            _todos.add(todo)
            return todo
        }

        override suspend fun updateTodo(todo: Todo): Todo {
            if (!shouldSucceed) throw Exception("Failed to update todo")
            val index = _todos.indexOfFirst { it.id == todo.id }
            if (index >= 0) {
                _todos[index] = todo
            }
            return todo
        }

        override suspend fun deleteTodo(todoId: String): Boolean {
            if (!shouldSucceed) throw Exception("Failed to delete todo")
            val initialSize = _todos.size
            _todos.removeAll { it.id == todoId }
            return _todos.size < initialSize
        }
    }

    // State definitions
    sealed interface AppState : State {
        data object Idle : AppState
        data object Loading : AppState
        data class Loaded(val todos: List<Todo>) : AppState
        data class Editing(val editingTodo: Todo, val allTodos: List<Todo>) : AppState
        data class Error(val message: String) : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data object LoadTodos : AppAction
        data class AddTodo(val title: String) : AppAction
        data class ToggleCompletion(val todoId: String) : AppAction
        data class DeleteTodo(val todoId: String) : AppAction
        data class StartEditing(val todoId: String) : AppAction
        data class UpdateEditingTitle(val newTitle: String) : AppAction
        data object SaveEdit : AppAction
        data object CancelEdit : AppAction
        data object RetryFromError : AppAction
    }

    // Store factory
    private fun createTestStore(
        repository: TodoRepository = MockTodoRepository(true),
        initialState: AppState = AppState.Idle,
    ): Store<AppState, AppAction, Nothing> {
        return Store(initialState) {
            coroutineContext(Dispatchers.Unconfined)

            // Idle state handling
            state<AppState.Idle> {
                action<AppAction.LoadTodos> {
                    nextState { AppState.Loading }
                }
            }

            // Loading state handling
            state<AppState.Loading> {
                enter {
                    val todos = repository.loadTodos()
                    nextState { AppState.Loaded(todos) }
                }
            }

            // Loaded state handling
            state<AppState.Loaded> {
                action<AppAction.AddTodo> {
                    val newTodo = Todo(
                        id = generateId(),
                        title = action.title,
                        completed = false,
                    )
                    val savedTodo = repository.addTodo(newTodo)
                    nextState { state.copy(todos = state.todos + savedTodo) }
                }

                action<AppAction.ToggleCompletion> {
                    val todoToUpdate = state.todos.first { it.id == action.todoId }
                    val updatedTodo = todoToUpdate.copy(completed = !todoToUpdate.completed)
                    val savedTodo = repository.updateTodo(updatedTodo)

                    nextState {
                        // Create updated todo list
                        val updatedTodoList = state.todos.map {
                            if (it.id == action.todoId) savedTodo else it
                        }

                        state.copy(todos = updatedTodoList)
                    }
                }

                action<AppAction.DeleteTodo> {
                    val success = repository.deleteTodo(action.todoId)
                    if (success) {
                        nextState { state.copy(todos = state.todos.filter { it.id != action.todoId }) }
                    }
                }

                action<AppAction.StartEditing> {
                    val todoToEdit = state.todos.first { it.id == action.todoId }
                    nextState { AppState.Editing(todoToEdit, state.todos) }
                }
            }

            // Editing state handling
            state<AppState.Editing> {
                action<AppAction.UpdateEditingTitle> {
                    nextState { state.copy(editingTodo = state.editingTodo.copy(title = action.newTitle)) }
                }

                action<AppAction.SaveEdit> {
                    // Save edited content to repository
                    val savedTodo = repository.updateTodo(state.editingTodo)

                    nextState {
                        // Create updated todo list with the edited task
                        val updatedTodoList = state.allTodos.map {
                            if (it.id == savedTodo.id) savedTodo else it
                        }

                        // Transition from editing state to loaded state
                        AppState.Loaded(updatedTodoList)
                    }
                }

                action<AppAction.CancelEdit> {
                    nextState { AppState.Loaded(state.allTodos) }
                }
            }

            // Error state handling
            state<AppState.Error> {
                action<AppAction.RetryFromError> {
                    nextState { AppState.Idle }
                }
            }

            // Global error handling
            state<AppState> {
                recover<Exception> {
                    nextState { AppState.Error(error.message ?: "Unknown error") }
                }
            }
        }
    }

    @Test
    fun todoApp_initialState() = runTest(testDispatcher) {
        val store = createTestStore()
        assertIs<AppState.Idle>(store.currentState)
    }

    @Test
    fun todoApp_loadTodosSuccess() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val store = createTestStore(repository)

        store.dispatch(AppAction.LoadTodos)

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        assertEquals(3, loadedState.todos.size)
    }

    @Test
    fun todoApp_loadTodosFailure() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = false)
        val store = createTestStore(repository)

        store.dispatch(AppAction.LoadTodos)

        assertIs<AppState.Error>(store.currentState)
        val errorState = store.currentState as AppState.Error
        assertEquals("Failed to load todos", errorState.message)
    }

    @Test
    fun todoApp_addTodo() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val store = createTestStore(repository, AppState.Loaded(repository.initialTodos))

        val initialCount = (store.currentState as AppState.Loaded).todos.size
        store.dispatch(AppAction.AddTodo("Buy groceries"))

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        assertEquals(initialCount + 1, loadedState.todos.size)
        assertEquals("Buy groceries", loadedState.todos.last().title)
        assertFalse(loadedState.todos.last().completed)
    }

    @Test
    fun todoApp_toggleTodoCompletion() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val store = createTestStore(repository, AppState.Loaded(repository.initialTodos))

        val todoToToggle = (store.currentState as AppState.Loaded).todos.first()
        val initialCompletionStatus = todoToToggle.completed

        store.dispatch(AppAction.ToggleCompletion(todoToToggle.id))

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        val updatedTodo = loadedState.todos.first { it.id == todoToToggle.id }
        assertEquals(!initialCompletionStatus, updatedTodo.completed)
    }

    @Test
    fun todoApp_deleteTodo() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val store = createTestStore(repository, AppState.Loaded(repository.initialTodos))

        val todoToDelete = (store.currentState as AppState.Loaded).todos.first()
        val initialCount = (store.currentState as AppState.Loaded).todos.size

        store.dispatch(AppAction.DeleteTodo(todoToDelete.id))

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        assertEquals(initialCount - 1, loadedState.todos.size)
        assertFalse(loadedState.todos.any { it.id == todoToDelete.id })
    }

    @Test
    fun todoApp_startEditingTodo() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val store = createTestStore(repository, AppState.Loaded(repository.initialTodos))

        val todoToEdit = (store.currentState as AppState.Loaded).todos.first()

        store.dispatch(AppAction.StartEditing(todoToEdit.id))

        assertIs<AppState.Editing>(store.currentState)
        val editingState = store.currentState as AppState.Editing
        assertEquals(todoToEdit.id, editingState.editingTodo.id)
        assertEquals(todoToEdit.title, editingState.editingTodo.title)
    }

    @Test
    fun todoApp_saveEditedTodo() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val todoToEdit = repository.initialTodos.first()
        val editedTodo = todoToEdit.copy(title = "Updated task")
        val store = createTestStore(repository, AppState.Editing(editedTodo, repository.initialTodos))

        store.dispatch(AppAction.SaveEdit)

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        val updatedTodo = loadedState.todos.first { it.id == todoToEdit.id }
        assertEquals("Updated task", updatedTodo.title)
    }

    @Test
    fun todoApp_cancelEditing() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = true)
        val todoToEdit = repository.initialTodos.first()
        val editedTodo = todoToEdit.copy(title = "This edit will be discarded")
        val store = createTestStore(repository, AppState.Editing(editedTodo, repository.initialTodos))

        store.dispatch(AppAction.CancelEdit)

        assertIs<AppState.Loaded>(store.currentState)
        val loadedState = store.currentState as AppState.Loaded
        val unchangedTodo = loadedState.todos.first { it.id == todoToEdit.id }
        assertEquals(todoToEdit.title, unchangedTodo.title)
    }

    @Test
    fun todoApp_errorRetry() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = false)
        val store = createTestStore(repository, AppState.Error("Test error"))

        // Change repository to succeed on retry
        repository.shouldSucceed = true

        store.dispatch(AppAction.RetryFromError)

        // Should be back to idle, ready to load
        assertIs<AppState.Idle>(store.currentState)

        // Try loading again
        store.dispatch(AppAction.LoadTodos)

        // Should succeed this time
        assertIs<AppState.Loaded>(store.currentState)
    }

    @Test
    fun todoApp_errorHandling() = runTest(testDispatcher) {
        val repository = MockTodoRepository(shouldSucceed = false)
        val store = createTestStore(repository, AppState.Loaded(repository.initialTodos))

        // Force an error by trying to add a todo when repository is set to fail
        store.dispatch(AppAction.AddTodo("This will fail"))

        assertIs<AppState.Error>(store.currentState)
        val errorState = store.currentState as AppState.Error
        assertEquals("Failed to add todo", errorState.message)
    }
}
