# actron-compose

Render an ordinary Actron Store with Compose Multiplatform. `ViewStore` collects state,
dispatches actions and collects transient events; it does not create a second state machine.
This module depends on core, not on the statechart or debug inspector.

## Dependency

See [installation](../README.md#installation) for the fork's composite-build setup.

```kotlin
implementation("io.github.roman-n1:actron-compose:1.0.0-beta.1")
```

## Render the counter

Use `CounterState`, `CounterAction` and `counterStore` from the
[root example](../README.md#1-a-store-for-a-counter). The owner creates the Store once and
passes it to the UI:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import actron.compose.rememberViewStore
import actron.core.Store

@Composable
fun CounterScreen(store: Store<CounterState, CounterAction, Nothing>) {
    val viewStore = rememberViewStore(store)
    val count = viewStore.select { it.count }
    Text("Count: $count")
    Button(onClick = { viewStore.dispatch(CounterAction.Increment) }) {
        Text("Increment")
    }
}
```

`select` derives a value with a pure mapper; readers recompose when that value changes.
Use `viewStore.state` when you need the complete current state.

## Pick the helper you need

| Helper | Purpose |
|---|---|
| `rememberViewStore(store)` | Collect an externally owned Store; does not close it by default |
| `rememberViewStore(store, autoClose = true)` | Close this Store when the composition leaves, if the composition owns its lifetime |
| `viewStore.stateContent<S2> { … }` | Render one sealed-state variant with a narrowed type |
| `viewStore.eventEffect<E2> { … }` | Collect transient events of a selected type |
| `ViewStore(state, dispatch = …)` | A fixed initial value for previews and UI tests |
| `rememberStateSaver<S>()` | Compose-backed state saving; see its [source contract](src/commonMain/kotlin/actron/compose/StateSaver.kt) |

Do not create a new Store on every recomposition. A navigation/retained owner usually owns
the Store, so leaving and re-entering the composition need not discard it. Transient events
are not replayed to collectors that appear later. For retained Machine effects, use
[actron-statechart-compose](../actron-statechart-compose/README.md).

See the [Compose reference](../doc/guides/store-api.md#compose) and
[ViewStore source](src/commonMain/kotlin/actron/compose/ViewStore.kt) for complete helper contracts.

## Android lifecycle verification

The device suite uses a real `ComponentActivity`, `ActivityScenario.recreate()` and
real Actron Stores. It checks retained snapshots across configuration recreation,
discarding snapshots when a screen is removed, independent keyed instances,
Store replacement, and externally owned Store lifetime. Run it on a connected
emulator or device:

```shell
./gradlew :actron-compose:connectedAndroidDeviceTest
```

The `Android Compose lifecycle` CI workflow runs the suite on an API 35 emulator.
`rememberStateSaver()` retains **snapshots**, not the Store itself. A Store created
inside the composition with `autoClose = true` closes on Activity recreation and
a new Store restores the retained snapshot. A Store owned by a ViewModel should
use `autoClose = false`; its owner is responsible for closing it.

This suite covers configuration changes and screen lifetime. Process death needs
a persistent `StateSaver`; the in-memory retained saver does not survive it.

## UI collection independent of processing

`store.state.collectAsUiState()` collects a `StateFlow` on `Dispatchers.Main.immediate`, tied
to the composition lifetime. Use it when a machine may process actions on a worker dispatcher
but Compose state must be updated on Main. The host must provide Main and create the composition
on its platform UI thread. See the [MVI threading contract](../doc/guides/machine-mvi.md).
