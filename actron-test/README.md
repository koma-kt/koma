# actron-test

Runtime test helpers shared by Actron machines and the internal Store engine.
Await startup/processing, observe states and events, patch configuration before startup,
and inspect which handlers match an action.

For pure Machine commands, clocks and executor checkpoints, use
[actron-statechart-test](../actron-statechart-test/README.md) instead.

## Dependency

Follow [installation](../README.md#installation). Add this to the test source set:

```kotlin
commonTest.dependencies {
    implementation("io.github.roman-n1:actron-test:1.0.0-beta.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
```

## Test the counter

Reuse `counterStore`, `CounterState` and `CounterAction` from the
[root counter example](../README.md#1-a-store-for-a-counter):

```kotlin
import actron.test.dispatchAndAwait
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@Test
fun incrementChangesTheState() = runTest {
    val store = counterStore(this)
    try {
        store.dispatchAndAwait(CounterAction.Increment)
        assertEquals(CounterState(1), store.currentState.context)
    } finally {
        store.close()
    }
}
```

`dispatchAndAwait` waits for this input's processing. It is different from `awaitIdle()`,
which also waits for finite state-scoped launches to settle. Long-lived `subscribe` work is
excluded from settling; timers and Machine command queues need the Machine test driver.

| Helper | Use |
|---|---|
| `startAndAwait()` | Finish startup before assertions or external messages |
| `dispatchAndAwait(action)` | Finish one action's processing |
| `awaitIdle()` / `pendingWork()` | Settle/report ordinary pending inputs and finite launches |
| `createRecorder()` | Observe state changes and emitted events from now onward |
| Recorder `receiveEvent` / `assertNoUnconsumedEvents()` | Consume expected effects and detect leftovers |
| `patch { … }` | Change test configuration before startup is requested |
| `diagnoseActionMatches` / `describeHandlers` | Inspect handler matching and declared metadata |

Create the recorder before dispatching events you expect it to capture. Awaiting the Store
from its own handler/observer would deadlock and is rejected. Inject a test scheduler through
the Store's owner scope, as in the example.
See [StoreExtensions](src/commonMain/kotlin/actron/test/StoreExtensions.kt),
[StoreRecorder](src/commonMain/kotlin/actron/test/StoreRecorder.kt) and the
[full testing reference](../doc/guides/store-api.md#testing-store).
