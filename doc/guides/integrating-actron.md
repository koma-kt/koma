# Integrating Actron into an existing application

Start with one feature such as contact search. Keep repositories, navigation and DI in
the host. Use one BOM version and check that the app resolves the intended Kotlin,
Compose and Actron variants. Run the published consumer with the host's toolchain,
then build the actual application, including its minified Android release and iOS
device link. The standalone consumer cannot exercise the host's dependency graph,
R8 configuration or device lifecycle.

## Ownership and overload

- Give each machine one owner. In Decompose, use a stable key scoped to the feature's
  business identity. The keeper owns the Store. The current component owns its
  subscriptions and routes effects through its current endpoint. Render a projection
  of the snapshot without writing a second copy of the same state.
- For busy ingress use `MachineStore(..., admission = AdmissionPolicy.Bounded(n))`
  and decide what the UI or producer does with each `admit` result. `Rejected` can
  mean debounce, refresh or show backpressure according to the action. `Closed` is
  terminal. `Accepted` means queued. Internal results and timers bypass this limit,
  so measure those producers too. If a retained Store needs a custom admission
  policy, use the explicit keeper factory path in the [Decompose guide](../../actron-decompose/README.md#component-wiring).
- Map expected repository failures to typed actions and propagate cancellation.
  Configure an exception handler at Store creation for unexpected failures. Avoid
  blocking I/O in decision rules or Store hooks.
- Register transient event observers before startup if startup can emit. Use
  retained effects for UI delivery across component recreation. Keep business
  intent in storage for delivery across process death. Close the owner on teardown
  and use `closeAndJoin` from a surviving scope when a replacement must wait.
- If restoring a checkpoint, version the application payload and machine definition.
  Explicitly handle incompatible or corrupt snapshots. A read-only screen can
  reload from its repository. Durable pending operations require reconciliation.

Exercise request cancellation and late results, component recreation, owner teardown,
overload and process restart on the pilot feature. Measure latency and memory on a
representative device before expanding to a high-volume feature.

## Durable writes

For sending a message, allocate a stable client message ID and insert a pending row
in the application's existing outbox transaction. The worker reads the row, submits
with that ID as an idempotency key, and records the server receipt in a transaction.
The Store observes or projects those outcomes. Leaving the screen cancels its
subscription and screen-scoped reads. The outbox worker owns the write lifetime.

If a feature uses `DurableMachine` as the outbox owner, implement
`DurableMachineStorage` with atomic generation comparison and checkpoint/outbox
commit. Start external work only after that commit. Reuse the same key after a
timeout or process restart. Retain receipts for the retry horizon. If the server
cannot deduplicate or look up a request by key, an ambiguous result needs
reconciliation before retrying.

Verify against the actual DB and service: kill before the network call, kill after
server acceptance but before local acknowledgement, then restart and replay the
same key. Assert one server message and one reconciled local receipt. Exercise two
competing workers and scope exit while a write is pending. The library's
`DurableMachineTest` covers the executor contract with an in-memory adapter.
