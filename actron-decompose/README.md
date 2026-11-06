# Actron + Decompose

`actron-decompose` installs an Actron machine in a Decompose component with one call and
binds Store observation to Essenty lifecycle destruction. Statechart is the standard machine
implementation and is exposed transitively; no second Decompose adapter module is required.
The module does not depend on persistence codecs, time travel or Compose.
The core module has no dependency on Decompose.

Use `implementation(project(":actron-decompose"))` from a checkout. The artifact is configured as
`io.github.roman-n1:actron-decompose` with the repository's version; these local changes have not
been published. See the root installation guide for release availability.

## One-call machine installation

Given your machine definition and command handler:

```kotlin
val store = componentContext.retainedMachine(
    key = "city-search",
    machine = CitySearchMachine.create(),
    initial = CitySearchState.Idle(),
    handler = citySearchHandler(repository),
)
```

Import `actron.decompose.retainedMachine`. The helper retains one Store and its command scope
per keeper/key, configures it, then starts it. The default processor is `Dispatchers.Main`;
pass `coroutineContext` for another owning dispatcher or tests. A retained key keeps the original
Store and dependencies; use a unique stable key per machine/business identity. Creation and
keeper destruction run on the keeper's owning thread. Handlers must not capture a component,
endpoint or a component-lifecycle scope.

Two independent optional parameters extend that same call:

```kotlin
restoration = session.searchRestoration(), // App-owned checkpoint and checkpoint tracking.
recording = history.recording(),          // Optional diagnostic observers/profiling.
```

`MachineRestoration` lazily supplies a checkpoint, its clock and tracking observers.
`MachineRecording` lazily binds diagnostic observers and optional handler/Store instrumentation.
Both bind only for a newly retained instance, before startup. Restoration works without recording;
recording can be used without restoration. Neither option is a global singleton or a second Store.
The library does not prescribe a serialization format or a debugger implementation.

`initialActions` are dispatched once after a fresh Store starts. They are skipped on checkpoint
resumption and host recreation. A mismatching definition/version is rejected before startup.
Destroying the keeper closes the Store and cancels its scope; construction failure cancels the
new scope too. The Weather application demonstrates the minimal path, checkpoint-only operation,
and the same feature with diagnostics enabled in its `debugTools` module.

## Two lifetimes

- The Store belongs to `InstanceKeeper`. Reuse it across component recreation; close it when the
  keeper is destroyed. Essenty already provides `getOrCreateCloseable`, and Store is AutoCloseable.
- Subscriptions that capture a component, endpoint or UI belong to that component's lifecycle.
  Destroying it stops those subscriptions without closing the retained Store.

Do not retain a component or its BridgeEndpoint inside Store handlers. Inject repositories and
other long-lived ports into the Store factory; attach the current endpoint after obtaining the
retained Store. Do not parent the retained Store to a scope cancelled by the old UI lifecycle.

## Component wiring

The application keeps its existing ComponentContext, UIEvent, Action/News and typed BridgeEndpoint
contracts. The following wiring assumes the application's existing Store factory and event mappers:

```kotlin
import actron.decompose.observeEvent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreateCloseable
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class CounterComponentImpl(
    componentContext: ComponentContext,
    private val endpoint: BridgeEndpoint<ParentAction, CounterNews>,
    storeFactory: CounterStoreFactory,
    componentScope: CoroutineScope,
) : ComponentContext by componentContext, CounterComponent {
    private val store = instanceKeeper.getOrCreateCloseable(key = "counter-store") {
        storeFactory.create() // Returns a passive Store; no captured component or endpoint.
    }

    override val state = store.state // One owner; no second MutableStateFlow to synchronise.

    init {
        store.observeEvent(lifecycle) { event ->
            endpoint.news.tryEmit(event.toNews()) // Handle a false result using the app's policy.
        }
        val actions = endpoint.actions.onEach { store.dispatch(it.toStoreAction()) }
            .launchIn(componentScope)
        lifecycle.doOnDestroy { actions.cancel() }
    }

    override fun onUiEvent(event: UiEvent) {
        store.dispatch(event.toStoreAction())
    }
}
```

Use a stable unique keeper key for each Store and its feature/business identity. A different key
creates another retained instance. As with Essenty's helper, use the same Store type for a given key.
If the external component state differs from Store state, expose an immutable projection rather
than maintaining another independently writable copy. UI rendering and routing stay in the app.

## Observation contracts

```kotlin
import actron.decompose.observeState
import actron.decompose.observeEvent

val states = store.observeState(componentContext.lifecycle) { state -> /* consume */ }
val events = store.observeEvent(componentContext.lifecycle) { event -> /* consume */ }
states.close()
events.close()
```

Both handles can be closed repeatedly; lifecycle destruction also closes them. Manual close
unsubscribes the lifecycle callback. An already destroyed lifecycle does not register with the
Store. A lifecycle destroyed synchronously by an initial state callback is handled too.

Register and close these handles on the lifecycle's owning thread. The callback itself retains
the Store's execution context; it is not switched to Main. State observation follows the existing
startup policy and is asynchronous/conflated. Event observation is ready before returning, does
not start the Store and does not replay past events. Attach it before startup or dispatching the
initial action. Observation is part of the Store contract; event registration completes before returning.

InstanceKeeper.onDestroy is synchronous: it calls Store.close(), without blocking the lifecycle
thread. When an external coroutine must await all cleanup, use the core closeAndJoin extension.
Do not launch that wait in a scope being destroyed. Retention is not process-death restoration:
StateKeeper/StateSaver schemas and business-ID restoration remain the application's responsibility.

## Verification

Common tests exercise two DefaultComponentContexts sharing a keeper, old endpoint-observer
cancellation, new observers receiving retained state, keeper shutdown, independent handle close,
and destroyed/reentrant lifecycle registration. They also verify that event observation is ready
before startup without starting the Store, and closing one event handle leaves sibling event/state
observers alive. Tests run on JVM, JS, Wasm, iOS and Android host.
