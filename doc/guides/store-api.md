# Internal Store engine reference

Maintainer reference for the internal Store engine, not an application API.
The standalone `Store(...)` factories have been removed. Internal modules use
`createRuntimeStore` with `@OptIn(InternalActronApi::class)`. Applications use our
[StateChart MachineStore](machine-mvi.md).

Detailed scheduling, plugin and testing contracts follow.
Start with the [project overview](../../README.md) or the [core quick start](../../actron-core/README.md).
Dependency setup and release availability are explained in the [installation guide](../../README.md#installation).

## Cancellable observation and shutdown

Import `actron.core.observeState`, `actron.core.observeEvent` and `actron.core.closeAndJoin`:

```kotlin
val stateObservation = store.observeState { state -> render(state) }
val eventObservation = store.observeEvent { event -> handle(event) }

// Stop each observer independently; repeated close calls are safe.
stateObservation.close()
eventObservation.close()

// From an external coroutine, stop the Store and await all cancellation cleanup.
store.closeAndJoin()
```

Callbacks run in the Store's execution context. They do not automatically switch to a UI thread.
State observation is registered asynchronously and remains conflated. Event observation registers
before returning, so it can be attached before startup or dispatch; past events are not replayed.
State observation follows the Store's existing startup policy; event observation alone does not
start it. Closing a handle requests cancellation without waiting: a callback already executing
may finish. Callback failures use the existing exception handling policy and end that observer.
Observation and closeAndJoin are members of Store; returned handles cancel individual observers.

`closeAndJoin` is cancellable while waiting; the Store remains closed if the caller is cancelled.
It waits for all owned work, including subscriptions, plugin work and non-cancellable cleanup.
Calling it from the Store's own coroutine throws before closing, including from launch/transaction
or plugin work; use `close()` there. Waiting for another Store is supported. Closing a passive
Store does not restore state or start handlers.

These operations are mandatory members of Store. Core Store, StateChartStore, MachineStore and
InvokedMachineStore factories implement the same contract. Custom stores and wrappers implement
or delegate observation and closeAndJoin along with the rest of Store.

## Quick Look

Describe each screen state with the `createRuntimeStore{}` DSL. Each `state{}` block groups the `enter{}`, `action{}`, and `recover{}` behavior that is valid for that state.
Sealed state variants make invalid UI states unrepresentable, such as verifying a code before it is complete or editing the code while verification is in progress.

```kt
sealed class VerificationState : State {
    abstract val code: String?
    abstract val isVerifyEnabled: Boolean
    abstract val isLoading: Boolean

    data class Draft(
        override val code: String? = null,
    ) : VerificationState() {
        override val isVerifyEnabled: Boolean = false
        override val isLoading: Boolean = false
    }

    data class Ready(
        override val code: String,
    ) : VerificationState() {
        override val isVerifyEnabled: Boolean = true
        override val isLoading: Boolean = false
    }

    data class Verifying(
        override val code: String,
    ) : VerificationState() {
        override val isVerifyEnabled: Boolean = false
        override val isLoading: Boolean = true
    }
}

sealed interface VerificationAction : Action {
    data class CodeChanged(val code: String) : VerificationAction
    data object Verify : VerificationAction
}

sealed interface VerificationEvent : Event {
    data object Verified : VerificationEvent
    data class ShowMessage(val message: String) : VerificationEvent
}

class VerificationViewModel(private val repository: VerificationRepository) : ViewModel() {

    val store: Store<VerificationState, VerificationAction, VerificationEvent> = createRuntimeStore(VerificationState.Draft()) {

        coroutineContext(viewModelScope.coroutineContext)

        state<VerificationState.Draft> {
            action<VerificationAction.CodeChanged> {
                nextState { verificationStateFor(action.code) }
            }
        }

        state<VerificationState.Ready> {
            action<VerificationAction.CodeChanged> {
                nextState { verificationStateFor(action.code) }
            }

            action<VerificationAction.Verify> {
                nextState { VerificationState.Verifying(code = state.code) }
            }
        }

        state<VerificationState.Verifying> {
            enter {
                repository.verify(code = state.code)
                event(VerificationEvent.Verified)
                nextState { VerificationState.Draft() }
            }

            recover<Exception> {
                event(VerificationEvent.ShowMessage(error.message ?: "Verification failed."))
                nextState { VerificationState.Ready(code = state.code) }
            }
        }
    }

    private fun verificationStateFor(input: String): VerificationState {
        val code = input.trim()
        return if (code.length == 6) {
            VerificationState.Ready(code = code)
        } else {
            VerificationState.Draft(code = code.ifEmpty { null })
        }
    }
}
```

## When Actron Fits Best

Actron works especially well when a feature has multiple explicit UI or business states and the transition rules between them are important.
By combining Kotlin `sealed class`/`sealed interface` with Actron's state machine DSL, you can keep each state's `enter{}`, `action{}`, `exit{}`, and `recover{}` behavior close together and make the transition rules easy to follow.

## Current Scope

Actron currently focuses on the core pieces of state management: explicit state transitions, coroutine-based asynchronous work, state persistence, and plugin-driven extensions such as logging and inter-store messaging.
It keeps surrounding helper layers intentionally small, so dependencies and feature composition can stay in ordinary Kotlin, while the core Store logic remains portable across platforms.

## Table of Contents

- [Quick Look](#quick-look)
- [When Actron Fits Best](#when-actron-fits-best)
- [Current Scope](#current-scope)
- [Installation](#installation)
- [Usage](#usage)
  - [Basic](#basic)
  - [Delivering events to the UI](#delivering-events-to-the-ui)
  - [Access repositories and UseCase classes](#access-repositories-and-usecase-classes)
  - [Multiple states and transitions](#multiple-states-and-transitions)
  - [Error handling](#error-handling)
  - [Asynchronous Work](#asynchronous-work)
  - [Specifying coroutineContext](#specifying-coroutinecontext)
    - [Specifying CoroutineDispatchers](#specifying-coroutinedispatchers)
  - [State Persistence](#state-persistence)
  - [Clear Pending Actions](#clear-pending-actions)
  - [Using Control Flow in createRuntimeStore{}](#using-control-flow-in-store)
  - [For Platforms Without Flow/StateFlow Access](#for-platforms-without-flowstateflow-access)
- [Compose](#compose)
  - [Rendering with State](#rendering-with-state)
  - [Dispatch Actions](#dispatch-actions)
  - [Handling Events](#handling-events)
  - [Mocks for preview and testing](#mocks-for-preview-and-testing)
- [Plugin](#plugin)
  - [Logging](#logging)
  - [Message](#message)
- [Project-specific AppStore Wrapper](#project-specific-appstore-wrapper)
- [Testing Store](#testing-store)

## Installation

```kt
implementation("io.github.roman-n1:actron-core:1.0.0-beta.1")
```

## Usage

### Basic

Let’s take a simple counter app as an example.
First, define the *State* and *Action* classes.

```kt
data class CounterState(val count: Int) : State

sealed interface CounterAction : Action {
    data object Increment : CounterAction
    data object Decrement : CounterAction
}
```

Create a *Store* using the `createRuntimeStore{}` DSL and an initial *State*.

```kt
val store: Store<CounterState, CounterAction, Nothing> = createRuntimeStore(CounterState(count = 0)) {}

// or, use the initialState() specification
val store: Store<CounterState, CounterAction, Nothing> = createRuntimeStore {

    initialState(CounterState(count = 0))
}
```

Define how *Action*s change *State* using the `state{}` and `action{}` blocks.
Specify the resulting *State* with `nextState { ... }`.

```kt
val store: Store<CounterState, CounterAction, Nothing> = createRuntimeStore(CounterState(count = 0)) {

    state<CounterState> {

        action<CounterAction.Increment> {
            nextState { state.copy(count = state.count + 1) }
        }

        action<CounterAction.Decrement> {
            if (0 < state.count) {
                nextState { state.copy(count = state.count - 1) }
            } else {
                // do not change State
            }
        }
    }
}
```

If `nextState { ... }` is not specified, the current state remains unchanged.
If `nextState { ... }` is called multiple times in the same handler, the last computed value is applied.

For conditional or complex updates, `nextState {}` can make the computation easier to read. The value of the block's final expression is used as the next state.

```kt
nextState {
    // ...
    val newCount = ...
    state.copy(count = newCount)
}
```

The *Store* setup is complete.
Keep the store instance in a ViewModel (or similar).

Dispatch an *Action* from the UI using the Store's `dispatch()` method.

```kt
// example in Compose
Button(
    onClick = { store.dispatch(CounterAction.Increment) },
) {
    Text(text = "increment")
}
```

The new *State* is exposed via the Store's `.state` (StateFlow), so render it in the UI.

### Delivering events to the UI

Define your *Event* class and set it as the third type parameter of *Store*.

```kt
sealed interface CounterEvent : Event {
    data class ShowToast(val message: String) : CounterEvent
    data object NavigateToNextScreen : CounterEvent
}
```

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState(count = 0)) {
    // ...
}
```

In an `action{}` block, specify an Event with `event()`.

```kt
action<CounterAction.Decrement> {
    if (0 < state.count) {
        nextState { state.copy(count = state.count - 1) }
    } else {
        event(CounterEvent.ShowToast("Can not Decrement.")) // raise event
    }
}
```

Collect the Store's `.event` (Flow) in the UI and handle it.

### Access repositories and UseCase classes

Have repositories and UseCase classes available in your store creation scope and use them inside `action{}` blocks.

```kt
fun CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState(count = 0)) {

    state<CounterState> {

        action<CounterAction.Load> {
            val count = counterRepository.get() // load
            nextState { state.copy(count = count) }
        }

        action<CounterAction.Increment> {
            val count = state.count + 1
            counterRepository.set(count) // save
            nextState { state.copy(count = count) }
        }

        // ...
    }
}

// or, define a Store class using delegation
class CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> by createRuntimeStore(
    initialState = CounterState(count = 0),
    builder = {
        state<CounterState> {
            // ...
        }
    },
)
```

<details>
<summary>TIPS: Define functions as needed</summary>

Processing other than changing the *State* may be defined as functions, as they tend to become complex and lengthy.

```kt
fun CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState(count = 0)) {

    // define as a function
    suspend fun loadCount(): Int {
        return counterRepository.get()
    }

    state<CounterState> {

        action<CounterAction.Load> {
            val count = loadCount() // call the function; nextState {} itself is not suspending
            nextState { state.copy(count = count) }
        }

        // ...
```

You may also define them as extension functions of *State* or *Action*.
</details>

### Multiple states and transitions

In the previous examples, the *State* was single.
If you need multiple *States* (for example, a UI during data loading), define them explicitly.

```kt
sealed interface CounterState : State {
    data object Loading : CounterState 
    data class Main(val count: Int) : CounterState
}
```

```kt
fun CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState.Loading) {

    state<CounterState.Loading> { // for Loading state
        action<CounterAction.Load> {
            val count = counterRepository.get()
            nextState { CounterState.Main(count = count) } // transition to Main state
        }
    }

    state<CounterState.Main> { // for Main state
        action<CounterAction.Increment> {
            // ...
```

In this example, the `CounterAction.Load` action needs to be issued from the UI when the application starts.
If you want to run logic when a *State* starts, use the `enter{}` block (similarly, you can use the `exit{}` block if necessary).

```kt
fun CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState.Loading) {

    state<CounterState.Loading> {
        enter {
            val count = counterRepository.get()
            nextState { CounterState.Main(count = count) } // transition to Main state
        }
    }

    state<CounterState.Main> {
        action<CounterAction.Increment> {
            // ...
```

The state diagram is as follows:

<div align="center">
  <img src="https://raw.githubusercontent.com/koma-kt/koma/main/doc/diagram.png" width=25% />
</div>
</br>

This framework's architecture can be easily visualized using state diagrams.
It would be a good idea to document it and share it with your development team.

#### Parent state/action handlers

You can also target a parent sealed type in `state<...>{}` or `action<...>{}` when the same handler should apply across multiple variants.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore(CounterState.Loading) {
    state<CounterState> {
        enter {
            // runs for all CounterState variants
        }
    }

    state<CounterState.Main> {
        action<CounterAction> {
            when (action) {
                CounterAction.Increment -> {
                    // ...
                }

                CounterAction.Decrement -> {
                    // ...
                }

                CounterAction.Load -> Unit
            }
        }
    }
}
```

Handler selection is first-match.
If both broad and specific handlers can match, the one registered earlier is used.
In practice, place broader handlers after more specific ones.

```kt
state<CounterState.Loading> {
    action<CounterAction.Load> {
        // specific handler
    }
}

state<CounterState> {
    action<CounterAction> {
        // broader fallback handler
    }
}
```

### Error handling

If you prepare a *State* for error display and handle the error in the `enter{}` block, it will be as follows:

```kt
sealed interface CounterState : State {
    // ...
    data class Error(val error: Exception) : CounterState
}
```

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    state<CounterState.Loading> {
        enter {
            try {
                val count = counterRepository.get()
                nextState { CounterState.Main(count = count) }
            } catch (e: Exception) {
                nextState { CounterState.Error(error = e) }
            }
        }
    }
}
```

This works, but you can also handle exceptions with the `recover{}` block.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    state<CounterState.Loading> {

        enter {
            // no error handling code
            val count = counterRepository.get()
            nextState { CounterState.Main(count = count) }
        }

        // more specific exceptions should be placed first
        recover<IllegalStateException> {
            // ...
            nextState { CounterState.Error(error = error) }
        }

        // more general exception handlers should come last
        recover<Exception> {
            // ...
            nextState { CounterState.Error(error = error) }
        }
    }
}
```

Exceptions can be caught not only in the `enter{}` block but also in the `action{}` and `exit{}` blocks.
In other words, your business logic exceptions can be handled in the `recover{}` block.

Exceptions that no `recover{}` handles, and failures of plugins and of the `StateSaver`, are reported to the handler set with `exceptionHandler()` (`ExceptionHandler.Rethrow` by default). Non-`Exception` throwables such as `Error`s are fatal: they abort the transition and are only reported.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    exceptionHandler(...)
}
```

You can also create an `ExceptionHandler` instance with the `ExceptionHandler()` factory function.

### Asynchronous Work

Handlers run one at a time: the *Store* processes dispatched actions in dispatch order. A `transaction{}` from a launched coroutine also runs exclusively with the handlers, but it is not ordered against queued actions: it takes the lock whenever it gets its turn.
You can use `launch{}` in both `enter{}` and `action{}` blocks to run asynchronous work and update *State* (or emit *Event*s).
This is useful for integrating long-running tasks such as flow collection, network calls, and background processing:

```kt
state<MyState.Active> {
    enter {
        // launch a coroutine that lives as long as this state is active
        launch {
            // collect from an external data source
            dataRepository.observeData().collect { newData ->
                // update state with the new data in a transaction
                transaction {
                    nextState { state.copy(data = newData) }
                }
            }
        }
    }
}
```

You can also start asynchronous work from an action:

```kt
state<MyState.Active> {
    action<MyAction.Refresh> {
        launch {
            // state updates in launch must be done in transaction{} block
            transaction {
                nextState { state.copy(isRefreshing = true) }
            }

            dataRepository.refresh()

            transaction {
                nextState { state.copy(isRefreshing = false) }
            }
        }
    }
}
```

This pattern lets your *Store* react to external data changes automatically, such as database updates, user preference changes, or network events.
Coroutines started by `launch{}` are automatically cancelled when the *State* changes to a different *State* variant (a different class, such as `Main` to `Loading`; an update from `Main(0)` to `Main(1)` keeps them running), making it easy to manage resources and subscriptions.
In `action{}`, `launch{}` is tied to the *State* active at action start.

If you want lightweight coordination and explicit cancellation for coroutines launched from an action handler, set the control directly on `launch(...)`:

```kt
val store = createRuntimeStore(MyState.Active()) {
    val searchLane = LaunchLane()

    state<MyState.Active> {
        action<MyAction.QueryChanged> {
            nextState { state.copy(query = action.query, isLoading = true) }

            launch(control = LaunchControl.CancelPrevious(searchLane)) {
                delay(300)
                val result = repository.search(action.query)
                transaction {
                    nextState {
                        state.copy(
                            result = result,
                            isLoading = false,
                        )
                    }
                }
            }
        }

        action<MyAction.ClearQuery> {
            cancelLaunch(searchLane)
            nextState {
                state.copy(
                    query = "",
                    result = emptyList(),
                    isLoading = false,
                )
            }
        }

        action<MyAction.Submit> {
            launch(control = LaunchControl.DropIfRunning()) {
                submit()
            }
        }
    }
}
```

`LaunchControl.CancelPrevious(lane)` cancels the previous tracked launch in the same lane before starting the next one.
`LaunchControl.DropIfRunning(lane)` ignores a new launch while tracked work in the same lane is still active.
When the lane is omitted, `LaunchControl.CancelPrevious()` and `LaunchControl.DropIfRunning()` use a default lane derived from the dispatched action's type, so launches coordinate across dispatches of that action type within the current state (in a broad `action<MyAction> {}` block, two different action types get two lanes).
`LaunchControl.Untracked` keeps the default behavior and runs launches independently.
A `transaction {}` requested by a launch that was cancelled (by `CancelPrevious`, `cancelLaunch(lane)` or a state exit) before the transaction got its turn is skipped, so a cancelled search never commits a stale result; once a transaction has started it runs to completion.
`cancelLaunch(lane)` only affects coroutines started from `action { launch { ... } }` in the current active state's runtime that use tracked controls such as `LaunchControl.CancelPrevious(...)` and `LaunchControl.DropIfRunning(...)`. Use an explicit `LaunchLane()` when you need to share a lane across multiple launches or cancel it later. It does not cancel `LaunchControl.Untracked` launches or `enter { launch { ... } }`.

### Specifying coroutineContext

The Store operates using Coroutines, and its default base CoroutineContext is `Dispatchers.Default`.
Specify it to align the Store's Coroutines lifecycle with another context or to change the execution thread.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    coroutineContext(...)
}
```

If you don’t use an auto-managed scope like ViewModel's `viewModelScope` or Compose's `rememberCoroutineScope()`, call Store's `.close()` method explicitly when the *Store* is no longer needed.
Then, processing of all Coroutines will stop.

#### Specifying CoroutineDispatchers

You can specify the execution thread (CoroutineDispatchers) in `enter{}`, `exit{}`, `action{}`, `recover{}`, and `launch{}` blocks, allowing you to locally control which thread each specific operation runs on.
If you omit the dispatcher parameter, Actron keeps using the Store's current execution context for that operation.

```kt
enter(Dispatchers.Default) {
    // work on CPU thread..

    launch(Dispatchers.IO) {
        // This code runs on IO thread
        val updates = dataRepository.observeUpdates()
        updates.collect { newData ->
            // ...
        }
    }
}
```

Alternatively, you can use Coroutines' `withContext()` for work the handler itself must finish before it returns; the *Store* keeps waiting for the handler.
Do not call `launch{}` inside `withContext()`: there it resolves to `CoroutineScope.launch`, so the handler would wait for that coroutine and never return. Pass the dispatcher to the *Store*'s `launch{}` instead.

```kt
enter {
    withContext(Dispatchers.Default) {
        // CPU work that must complete before this state counts as entered
    }
    launch(Dispatchers.IO) {
        // This coroutine runs on the IO thread and lives as long as this state is active
        dataRepository.observeUpdates().collect { newData ->
            transaction { nextState { state.copy(data = newData) } }
        }
    }
}
```

### State Persistence

You can prepare a [StateSaver](../../actron-core/src/commonMain/kotlin/actron/core/StateSaver.kt) to automatically handle *State* persistence:

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    stateSaver(...)
}
```

You can also create a `StateSaver` instance with the `StateSaver()` factory function.

`restore(initialState)` returns a saved state or the supplied initial state. For a saver that
only writes, use `StateSaver(save = { state -> persist(state) }, restore = { it })`.
The restore contract does not return `null`; see the [absence policy](absence-policy.md)
for the breaking API migration and the strict source/compiler checks that prevent nullable contracts.

### Clear Pending Actions

By default, Actron clears already queued actions when the store exits the current state and enters a different state variant.
To keep queued actions across state exits, set `pendingActionPolicy(PendingActionPolicy.Keep)`.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    pendingActionPolicy(PendingActionPolicy.Keep)
}
```

Regardless of the configured `PendingActionPolicy`, you can still discard already queued actions at a specific point by calling `clearPendingActions()` inside `enter{}`, `action{}`, `exit{}`, `recover{}`, or inside `transaction{}` from a launched coroutine.

### Using Control Flow in `createRuntimeStore{}`

The body of `createRuntimeStore{}` is ordinary Kotlin code, so you can use control flow such as `if` and `when` when specifying *Store* configuration.

```kt
fun CounterStore(
    logExceptions: Boolean,
): Store<CounterState, CounterAction, Nothing> = createRuntimeStore {
    initialState(CounterState(count = 0))

    if (logExceptions) {
        exceptionHandler(ExceptionHandler.Log)
    }
}
```

### For Platforms Without Flow/StateFlow Access

On platforms where Store's `.state` (StateFlow) and `.event` (Flow) cannot be consumed directly (e.g., iOS), use `.observeState()` and `.observeEvent()`.
If the *State* or *Event* changes, you will be notified through these callbacks.
These callbacks run in the Store's execution context. Actron does not automatically switch to a UI thread, so move to the appropriate UI thread before touching UI components when needed.

Store startup is lazy. By default, the Store starts on the first `.dispatch(...)` or when state collection begins through `.state` or `.observeState()`.
If you want state collection not to start the Store automatically, set `autoStartPolicy(AutoStartPolicy.OnDispatch)` and call `.start()` when you want to trigger startup explicitly.

## Compose

<details>
<summary>contents</summary>

You can use Store's `.state` (StateFlow), `.event` (Flow), and `.dispatch()` directly, but we provide a mechanism for Compose.

```kt
implementation("io.github.roman-n1:actron-compose:1.0.0-beta.1")
```

Create an instance of the `ViewStore` from a *Store* using the `rememberViewStore()` function.
For example, if you have a *Store* in ViewModels, it would look like this:

```kt
fun CounterStore(
    coroutineContext: CoroutineContext,
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    coroutineContext(coroutineContext)
}
```

```kt
@HiltViewModel
class CounterViewModel @Inject constructor(
    counterRepository: CounterRepository,
) : ViewModel() {

    val store = CounterStore(
        coroutineContext = viewModelScope.coroutineContext,
        counterRepository = counterRepository,
    )
}
```

```kt
@Composable
fun CounterScreen(
    // create an instance of ViewStore
    viewStore: ViewStore<CounterState, CounterAction, CounterEvent> = rememberViewStore(hiltViewModel<CounterViewModel>().store),
) {
    // ...

    // pass the ViewStore instance to lower components if necessary
    YourComposable(
        viewStore = viewStore,
    )
}
```

Alternatively, you can use the `createRuntimeStore{}` DSL directly in the ViewModel as follows, but note that in this case you need tests for `CounterViewModel`, and sharing a *Store* across multiple platforms becomes harder.

```kt
@HiltViewModel
class MainViewModel @Inject constructor(
    counterRepository: CounterRepository,
) : ViewModel() {

    val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
        // ...

        coroutineContext(viewModelScope.coroutineContext)
    }
}
```

You can create a `ViewStore` instance without using ViewModel as shown below:

```kt
fun CounterStore(
    coroutineContext: CoroutineContext,
    stateSaver: StateSaver<CounterState>,
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    coroutineContext(coroutineContext)
    stateSaver(stateSaver)
}
```

```kt
@Composable
fun CounterScreen() { // wrapper for the preview-friendly CounterScreen below
    val coroutineScope = rememberCoroutineScope()
    val stateSaver = rememberStateSaver<CounterState>()
    val viewStore: ViewStore<CounterState, CounterAction, CounterEvent> = rememberViewStore {
        CounterStore(
            coroutineContext = coroutineScope.coroutineContext, // or, specify the autoClose option in rememberViewStore{}
            stateSaver = stateSaver, // state persistence during screen rotation, etc.
            counterRepository = CounterRepositoryImpl(),
        )
    }
    CounterScreen(viewStore = viewStore)
}

@Composable
fun CounterScreen(
    viewStore: ViewStore<CounterState, CounterAction, CounterEvent>,
) {
    // ...
}
```

If you inject instances such as Repository using a DI library, it is useful to create a class like the following.

```kt
class CounterStoreContainer(
    private val counterRepository: CounterRepository,
) {
    fun build(coroutineContext: CoroutineContext, stateSaver: StateSaver<CounterState>): Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
        // ...

        coroutineContext(coroutineContext)
        stateSaver(stateSaver)
    }
}
```

### Rendering with State

If there’s a single *State*, just use ViewStore's `.state` property.

```kt
Text(
    text = viewStore.state.count.toString(),
)
```

If there are multiple *States*, use the `.stateContent()` method for the target *State*.

```kt
viewStore.stateContent<CounterState.Main> {
    Text(
        text = state.count.toString(),
    )
}
```

When drawing the UI, if it does not match the target *State*, the `.stateContent()` will not be executed.
Therefore, you can define components for each *State* side by side.

```kt
viewStore.stateContent<CounterState.Loading> {
    Text(
        text = "loading..",
    )
}

viewStore.stateContent<CounterState.Main> {
    Text(
        text = state.count.toString(),
    )
}
```

To project the state into what one composable needs, `select` derives a value from it: the
mapper runs when the state changes, and whatever reads the result recomposes only when the
derived value changed. A UI model from a large state, or one field of it, is read this way
without recomposing on every unrelated change.

```kt
val uiModel = viewStore.select { it.toUiModel() }   // a pure projection; readers recompose when uiModel changes
Text(text = uiModel.title)
```

If you use lower components in the `stateContent()` block, pass its instance.

```kt
viewStore.stateContent<CounterState.Main> {
    YourComposable(
        viewStore = this, // ViewStore instance for CounterState.Main
    )
}
```

```kt
@Composable
fun YourComposable(
    // Main state is confirmed
    viewStore: ViewStore<CounterState.Main, CounterAction, CounterEvent>,
) {
    Text(
        text = viewStore.state.count.toString()
    )
}
```

### Dispatch Actions

Use ViewStore's `.dispatch()` with the target *Action*.

```kt
Button(
    onClick = { viewStore.dispatch(CounterAction.Increment) },
) {
    Text(
        text = "increment"
    )
}
```

### Handling Events

Use ViewStore's `.eventEffect()` with the target *Event*.

```kt
viewStore.eventEffect<CounterEvent.ShowToast> { event ->
    // do something..
}
```

In the above example, you can also subscribe to the parent *Event* type.

```kt
viewStore.eventEffect<CounterEvent> { event ->
    when (event) {
        is CounterEvent.ShowToast -> // do something..
        is CounterEvent.GoBack -> // do something..
        // ...
```

### Mocks for preview and testing

Create an instance of `ViewStore` directly with the target *State*.

```kt
@Preview
@Composable
fun LoadingPreview() {
    MyApplicationTheme {
        CounterScreen(
            viewStore = ViewStore(
                state = CounterState.Loading,
            ),
        )
    }
}
```

Therefore, if you prepare only the *State*, it is possible to develop the UI.
</details>

## Plugin

<details>
<summary>contents</summary>

You can create extensions that work with the *Store*.
To do this, create a class that implements the `Plugin` interface and override the necessary methods.

```kt
class YourPlugin<S : State, A : Action, E : Event> : Plugin<S, A, E> {
    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        // do something..
    }
}
```

Apply the created plugin as follows:

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    // add Plugin instance
    plugin(YourPlugin())

    // or, implement Plugin directly here
    plugin(
        object : Plugin<CounterState, CounterAction, CounterEvent> {
            override suspend fun onState(scope: PluginScope<CounterState, CounterAction>, prevState: CounterState, state: CounterState) {
                // do something..
            }
        },
    )

    // add multiple Plugins
    plugin(..., ...)
}
```

Note that *State* is read-only in Plugin hooks.

You can also create a `Plugin` instance with the `Plugin()` factory function.

Plugin methods are suspending functions. The *Store* waits for plugin processing to complete before proceeding.
When multiple plugin instances are registered, Actron invokes them concurrently by default.
If plugins must run one by one in registration order, set `pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)`.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)
}
```

Because a long-running method can block the *Store*, start background work from the hook with `scope.launch { ... }`.

In the next section, we introduce built-in plugins.
The source code is the `:actron-logging` and `:actron-message` modules in this repository, so you can use it as a reference for your plugin implementation.

### Logging

Plugin for logging Store operations.

```kt
implementation("io.github.roman-n1:actron-logging:1.0.0-beta.1")
```

Apply the `simpleLogging()` plugin factory function to your *Store* to log actions, events, and state changes.

```kt
val store: Store<CounterState, CounterAction, CounterEvent> = createRuntimeStore {
    // ...

    plugin(simpleLogging())
}
```

For example, you can pass your own `Logger` to `simpleLogging()`:

```kt
plugin(
    simpleLogging(
        logger = YourLogger(),
    ),
)
```

If you want a different logging plugin shape entirely, implement your own Actron `Plugin` and reuse `Logger` or `DefaultLogger` from this module.

### Message

Plugin for sending messages between *Stores*.

```kt
implementation("io.github.roman-n1:actron-message:1.0.0-beta.1")
```

First, prepare classes for messages.

```kt
sealed interface MainMessage : Message {
    data object LoggedOut : MainMessage
    data class CommentLiked(val commentId: Int) : MainMessage
}
```

Apply the `receiveMessages()` plugin factory function to the *Store* that receives messages.

```kt
val myPageStore: Store<MyPageState, MyPageAction, MyPageEvent> = createRuntimeStore {
    // ...

    plugin(
        receiveMessages { message ->
            when (message) {
                MainMessage.LoggedOut -> dispatch(MyPageAction.doLogoutProcess)
                // ...
            }
        }
    )
}
```

Define the `message()` specification at any point in the *Store* that sends messages.

```kt
val mainStore: Store<MainState, MainAction, MainEvent> = createRuntimeStore {
    // ...

    state<MainState.LoggedIn> { // leave the logged-in state
        exit {
            message(MainMessage.LoggedOut)
        }
    }
}
```
</details>

## Project-specific AppStore Wrapper

In larger projects, it can be useful to wrap `createRuntimeStore{}` DSL in a project-specific `AppStore{}` that applies app-wide defaults in one place.
This lets you centralize shared *Store* configuration.

```kt
fun <S : State, A : Action, E : Event> AppStore(
    initialState: S,
    builder: StoreBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> = createRuntimeStore(
    initialState = initialState,
) {
    // shared Store configuration
    plugin(AppLoggingPlugin())
    exceptionHandler(AppExceptionHandler)

    builder()
}
```

A feature *Store* can then focus on its own state transitions and actions:

```kt
fun CounterStore(
    counterRepository: CounterRepository,
): Store<CounterState, CounterAction, CounterEvent> = AppStore( // use AppStore{}
    initialState = CounterState(count = 0),
) {
    state<CounterState> {
        // ...
    }
}
```

## Testing Store

Add `:actron-test` to your test source set to use Actron's test helpers.

```kt
commonTestImplementation("io.github.roman-n1:actron-test:1.0.0-beta.1")
```

Use `dispatchAndAwait(action)` to dispatch an *Action* and suspend until the *Store* finishes processing it. It waits for startup (when needed), the matching `action {}` handler, and the resulting synchronous state transition work, but not for additional work launched with `launch {}`.

```kt
@Test
fun counterStore_dispatchesAndProcesses() = runTest {
    // Given
    val store = CounterStore(...)

    // When
    store.dispatchAndAwait(CounterAction.Increment) // wait until the dispatched action completes

    // Then
    assertEquals(CounterState(count = 1), store.currentState)
}
```

If you want to inspect only the startup phase (plugin `onStart` hooks and the synchronous `enter {}` chain) without dispatching an *Action*, use `startAndAwait()`.

For most Store tests, use `createRecorder()` to create and attach the default `StoreRecorder`, then assert recorded state and event history.

```kt
@Test
fun counterStore_recordsStatesAndEvents() = runTest {
    // Given
    val store = CounterStore(...)
    val recorder = store.createRecorder()

    // When
    store.dispatchAndAwait(CounterAction.Increment)

    // Then
    assertEquals(
        listOf(
            CounterState(count = 0),
            CounterState(count = 1),
        ),
        recorder.states,
    )
    assertEquals(
        listOf(CounterEvent.Incremented(count = 1)),
        recorder.events,
    )
}
```

Or use `record { recorder -> ... }` to scope a recording session to a block. The *Store* is the receiver, so `dispatchAndAwait()` can be called without a prefix.

```kt
store.record { recorder ->
    dispatchAndAwait(CounterAction.Increment)
    assertEquals(listOf(CounterState(0), CounterState(1)), recorder.states)
}
```

To account for the events one by one, receive them from the recorder in order: `receiveEvent<E>()`
returns the next event, which must be of that type, or `receiveEvent { predicate }` the next one
that satisfies the predicate; a wrong one fails naming the event and the unconsumed tail. End the
test with `assertNoPendingWork(recorder)`: it fails when the Store still has inputs queued or
launches running (call `awaitIdle()` first when it may), or when the recorder holds events the test
did not receive. `events` keeps every event, received or not.

```kt
store.record { recorder ->
    dispatchAndAwait(CounterAction.Increment)
    assertEquals(CounterEvent.Incremented(count = 1), recorder.receiveEvent<CounterEvent.Incremented>())
    assertNoPendingWork(recorder)               // nothing running, nothing unreceived
}
```

If you need custom recording behavior, implement your own `Plugin` and register it via `patch { plugin(...) }`.
If your `action {}` or `enter {}` logic launches additional coroutines with `launch {}`, or if you need virtual time control, use test dispatcher and scheduler control separately.

Use `patch { ... }` when you want to replace non-state *Store* configuration in tests without rewriting the *Store* definition.

```kt
fun CounterStore(): Store<CounterState, CounterAction, Nothing> = createRuntimeStore(
    initialState = CounterState(count = 0),
) {
    plugin(AppLoggingPlugin())

    state<CounterState> {
        // ...
    }
}

val store = CounterStore().patch {
    clearPlugins()
    exceptionHandler(ExceptionHandler.Log)
}
```

Inside `patch` block, you can use these APIs:

- `initialState(...)`
- `coroutineContext(...)`
- `stateSaver(...)`
- `exceptionHandler(...)`
- `autoStartPolicy(...)`
- `pendingActionPolicy(...)`
- `pluginExecutionPolicy(...)`
- `plugin(...)`
- `clearPlugins()`
- `replacePlugins(...)`

To see the side effects of the work a handler started with `launch {}`, wait for the Store to
settle with `awaitIdle()`: it waits until every dispatched action, transaction and recovery
finished and every `launch {}` of `enter {}` and `action {}` ended, whatever dispatcher they run
on, and fails with what is still pending after its timeout. A `subscribe {}` (a Flow collection,
a socket reader: work that lives as long as its state) is not waited for; use it for what never
ends on its own, and `launch {}` for what finishes. `pendingWork()` reports the same without
waiting. Under `runTest` the timeout is virtual, so a Store on a real dispatcher is awaited under
`withContext(Dispatchers.Default)`.

```kt
@Test
fun search_loadsInTheBackground() = runTest {
    val store = SearchStore(...)                 // launch { repository.search(query) } inside action {}

    store.dispatchAndAwait(SearchAction.Submit("cats"))
    withContext(Dispatchers.Default) { store.awaitIdle() }   // the launch and what it transacted are done

    assertEquals(SearchState.Results(listOf("cat")), store.currentState)
}
```
