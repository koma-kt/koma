# actron-statechart-compose

Use this when a Machine effect must survive the UI temporarily leaving composition: for
example, an unhandled navigation request or confirmation dialog. Ordinary state rendering
still uses [actron-compose](../actron-compose/README.md); this module handles the Machine's
retained mailbox. Dependency setup follows [installation](../README.md#installation).

Retention is an acknowledgement/retry contract, not an exactly-once guarantee for external
operations. A recreated collector can receive an unacknowledged effect again; handle it
before acknowledging it and choose an application-appropriate retry policy.

Compose Multiplatform helpers for [actron-statechart](../actron-statechart/README.md)'s
`MachineStore`, on top of [actron-compose](../actron-compose): what a screen needs from a machine
that `ViewStore` does not cover. `actron-compose` stays core-only, as upstream keeps it; the
machine's concerns live here.

- **`MailboxEffect(mailbox) { delivery -> … }`**: subscribes the composition to the store's
  retained mailbox (handoff §10). The block runs for each `Delivery`, one at a time, oldest
  first. The caller acknowledges it once the UI did what the effect asked; the helper never
  acknowledges automatically. What was queued before the
  composition entered is delivered when it enters; what it was handling when it left,
  unacknowledged, waits for the next subscriber, which gets it again with `attempt + 1`, up to
  the policy's budget. One per mailbox at a time, at the screen that owns the store; the block
  handles every kind of effect the policy keeps, with a `when`.

The UI model of a screen is `viewStore.select { it.toUiModel() }` from `actron-compose`: a pure
projection of the snapshot, so nothing of it is here.

Status: **supported API**, in the fork [roman-n1/actron](https://github.com/roman-n1/actron).

## Dependency

```kotlin
implementation("io.github.roman-n1:actron-statechart-compose:1.0.0-beta.1")
```

The module brings `actron-compose` and `actron-statechart` with it (`api` dependencies). Targets:
Android, iOS (arm64, simulator arm64), JVM, JS and Wasm (browser).

## Quick start

```kotlin
@Composable
fun ChatScreen(store: MachineStore<ChatContext, ChatAction, ChatCommand, ChatEffect>, navigator: Navigator) {
    val viewStore = rememberViewStore(store)
    val uiModel = viewStore.select { it.toUiModel() }

    MailboxEffect(store.mailbox) { delivery ->
        when (val effect = delivery.event) {
            is ChatEffect.OpenThread -> navigator.open(effect.threadId)
            is ChatEffect.ConfirmDelete -> if (dialogs.confirm(effect)) viewStore.dispatch(ChatAction.DeleteConfirmed(effect.messageId))
        }
        delivery.acknowledge()                     // the screen took responsibility for it
    }

    ChatContent(uiModel, onSend = { viewStore.dispatch(ChatAction.Send(it)) })
}
```

A block that suspends (a dialog awaiting its answer) is cancelled when the screen leaves, and
the effect returns to the mailbox: the recreated screen shows the dialog again. A block that
throws fails the composition, as a `LaunchedEffect` does; the effect waits for the next
subscriber, and `EffectPolicy.Retained(maxAttempts)` bounds how many screens it may crash.

## Tests

`MailboxEffectTest` runs on every target with a composition on the test dispatcher (a
`Recomposer`, frames sent by hand, no UI): a delivery reaches the block and leaves on
acknowledgement, what was queued before is delivered on entering, a composition leaving
mid-handling hands the effect to the next one as attempt 2, the budget ends the redeliveries, the
latest block is used, replacing the mailbox cancels the old handler and releases its delivery,
and a throwing block fails the composition and releases the effect. A shared scenario interrupts
200 deliveries before acknowledgement while 400 effects flow, awaits every retry explicitly,
and acknowledges each exactly once. `MailboxEffectRaceJvmTest` runs that scenario with the
producer and store on real threads, without polling progress.
