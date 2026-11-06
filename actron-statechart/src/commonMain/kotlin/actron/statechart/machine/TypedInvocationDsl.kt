package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId

/**
 * Builds a typed parent and one state-scoped child composition. Declare invoke(child, ...) in
 * exactly one state. The result is the existing pure InvokedMachine, not a second live Store:
 * commit InvocationDecision before executing its parent/child commands and timers.
 */
fun <PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> typedInvokedMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: PC,
    child: Machine<CC, CA, CCMD, CE>,
    maxDeliveries: Int = 100,
    build: TypedStateMachineBuilder<PC, PC, PA, PCMD, PE>.(TypedInvocation<PC, PA, PCMD, PE, CC, CA, CCMD, CE>) -> Unit,
): InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE> {
    val invocation = TypedInvocation<PC, PA, PCMD, PE, CC, CA, CCMD, CE>(child, maxDeliveries)
    val builder = TypedStateMachineBuilder<PC, PC, PA, PCMD, PE>({ it }, { _, state -> state })
    builder.build(invocation)
    return invocation.bind(builder.build(id, version, initial))
}

/** A typed child declaration handle; it is bound to the state containing invoke. */
class TypedInvocation<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> internal constructor(
    private val child: Machine<CC, CA, CCMD, CE>,
    private val maxDeliveries: Int,
) {
    private class Registration<PC : Any, PA : Action, PE : Event, CC : Any, CA : Action, CE : Event>(
        val owner: StateId,
        val context: (MachineSnapshot<PC>) -> CC,
        val toChild: (PE, (CA) -> Unit) -> Unit,
        val toParent: (CE, (PA) -> Unit) -> Unit,
    )

    private val registrations = mutableListOf<Registration<PC, PA, PE, CC, CA, CE>>()

    internal fun register(
        owner: StateId,
        context: (MachineSnapshot<PC>) -> CC,
        toChild: (PE, (CA) -> Unit) -> Unit,
        toParent: (CE, (PA) -> Unit) -> Unit,
    ) {
        require(registrations.isEmpty()) { "[Actron] Declare invoke in exactly one owner state" }
        registrations += Registration(owner, context, toChild, toParent)
    }

    internal fun bind(parent: Machine<PC, PA, PCMD, PE>): InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE> {
        require(registrations.size == 1) { "[Actron] A typed invoked machine needs one invoke declaration" }
        val registration = registrations.single()
        return InvokedMachine(parent, registration.owner, child, registration.context, registration.toChild, registration.toParent, maxDeliveries)
    }
}
