package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.ActivationId
import actron.statechart.machine.ChildCheckpoint
import actron.statechart.machine.ChildInvocation
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EffectId
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.InvocationCheckpoint
import actron.statechart.machine.InvocationDecision
import actron.statechart.machine.InvocationInput
import actron.statechart.machine.LaneId
import actron.statechart.machine.Lanes
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.PendingEffect
import actron.statechart.machine.stateMachine
import actron.statechart.machine.typedInvokedMachine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InvocationPersistenceTest {
    @Serializable private sealed interface Parent {
        val seed: Int

        @Serializable data class Idle(
            override val seed: Int = 7,
        ) : Parent

        @Serializable data class Active(
            override val seed: Int,
        ) : Parent
    }

    @Serializable private sealed interface Act : Action {
        @Serializable data object Open : Act

        @Serializable data object Close : Act
    }

    @Serializable private data object Finish : Action

    @Serializable private data object Finished : Event

    private fun composition(
        parentVersion: String = "1",
        childVersion: String = "1",
    ) = typedInvokedMachine<Parent, Act, String, Finished, Int, Finish, String, Finished>(
        DefinitionId("parent"),
        DefinitionVersion(parentVersion),
        Parent.Idle(),
        child =
            stateMachine(DefinitionId("child"), DefinitionVersion(childVersion), StateId("Working")) {
                state(StateId("Working")) {
                    onEnter {
                        context += 1
                        command("first", LaneId("child-work"), ConcurrencyPolicy.Sequential)
                        command("second", LaneId("child-work"), ConcurrencyPolicy.Sequential)
                    }
                    onExit { event(Finished) }
                    on<Finish>(StateId("Done"))
                    after(10.seconds, StateId("Done"))
                }
                state(StateId("Done")) {}
            },
    ) { child ->
        state<Parent.Idle>(enter = { Parent.Idle(it.seed) }) { on<Act.Open, Parent.Active>() }
        state<Parent.Active>(enter = { Parent.Active(it.seed) }) {
            onEnter { command("parent-work") }
            on<Act.Open, Parent.Active>()
            on<Act.Close, Parent.Idle>()
            invoke(child, context = { it.context.seed }, toParent = { _, send -> send(Act.Close) })
        }
    }

    private fun lanes(commands: List<CommandRegistration<String>>): Lanes<String> = commands.fold(Lanes()) { lanes, command -> lanes.admit(command).lanes }

    // The pure invocation host captures both sides only after committing a composite decision.
    private fun checkpoint(
        decision: InvocationDecision<Parent, Int, String, Finished, String, Finished>,
        now: MachineTime = MachineTime.Zero,
    ): InvocationCheckpoint<Parent, String, Int, String> {
        assertIs<DecisionOutcome.Handled>(decision.outcome)
        val child =
            when (val current = decision.snapshot.child) {
                ChildInvocation.Dormant -> ChildCheckpoint.Dormant
                is ChildInvocation.Active -> ChildCheckpoint.Active(current.owner, ExecutorCheckpoint(current.snapshot, now, lanes(decision.childCommands.map { it.command })))
            }
        return InvocationCheckpoint(ExecutorCheckpoint(decision.snapshot.parent, now, lanes(decision.parentCommands)), child)
    }

    private fun opened(): InvocationCheckpoint<Parent, String, Int, String> {
        val machine = composition()
        val start = machine.decide(machine.initialSnapshot(Parent.Idle()), InvocationInput.Parent(MachineInput.Start(MachineTime.Zero)))
        return checkpoint(machine.decide(start.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, MachineTime.Zero))), MachineTime(4.seconds))
    }

    @Test
    fun restoresFreshDormantAndActiveInvocationThenRoutesResultsAndRejectsOldOwner() {
        val machine = composition()
        val persistence = invocationPersistence(machine)
        val fresh = InvocationCheckpoint<Parent, String, Int, String>(ExecutorCheckpoint.initial(machine.parent.initialSnapshot(Parent.Idle())))
        val restoredFresh = persistence.restore(persistence.save(fresh))
        val start = machine.decide(restoredFresh.snapshot, InvocationInput.Parent(MachineInput.Start(MachineTime.Zero)))
        val dormant = persistence.restore(persistence.save(checkpoint(start)))
        assertEquals(ChildInvocation.Dormant, dormant.snapshot.child)
        val active = checkpoint(machine.decide(dormant.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, MachineTime.Zero))), MachineTime(4.seconds))
        val restored = invocationPersistence(composition()).restore(persistence.save(active))
        assertEquals(active, restored)
        val child = assertIs<ChildCheckpoint.Active<Int, String>>(restored.child)
        assertEquals(8, child.checkpoint.snapshot.context, "Entry hook must not run again")
        assertEquals(
            "first",
            child.checkpoint.lanes.running.values
                .single()
                .command,
        )
        assertEquals(
            "second",
            child.checkpoint.lanes.queued.values
                .single()
                .single()
                .command,
        )
        assertEquals(
            "parent-work",
            restored.parent.lanes.running.values
                .single()
                .command,
        )
        // Local command IDs can coincide; owner and side preserve the distinction.
        assertEquals(restored.parent.registrations.keys, child.checkpoint.lanes.running.keys)
        val reentered = checkpoint(machine.decide(restored.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, restored.parent.now))), restored.parent.now)
        val again = persistence.restore(persistence.save(reentered))
        val next = assertIs<ChildCheckpoint.Active<Int, String>>(again.child)
        assertNotEquals(child.owner, next.owner)
        val stale =
            machine.decide(
                again.snapshot,
                InvocationInput.Child(
                    child.owner,
                    MachineInput.CommandResult(
                        child.checkpoint.lanes.running.keys
                            .single(),
                        Finish,
                        again.parent.now,
                    ),
                ),
            )
        assertEquals(again.snapshot, stale.snapshot)
        val finished =
            machine.decide(
                again.snapshot,
                InvocationInput.Child(
                    next.owner,
                    MachineInput.CommandResult(
                        next.checkpoint.lanes.running.keys
                            .single(),
                        Finish,
                        again.parent.now,
                    ),
                ),
            )
        assertIs<Parent.Idle>(finished.snapshot.parent.context)
        assertEquals(ChildInvocation.Dormant, finished.snapshot.child)
        assertEquals(next.owner, finished.childCancellations.single().owner)
        val closed = persistence.restore(persistence.save(checkpoint(finished, again.parent.now)))
        assertTrue(closed.parent.registrations.isEmpty())
        assertEquals(ChildCheckpoint.Dormant, closed.child)
        val newChild = machine.decide(closed.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, closed.parent.now)))
        assertNotEquals(next.owner, assertIs<ChildInvocation.Active<Int>>(newChild.snapshot.child).owner)
    }

    @Test
    fun restoredChildQueuePromotesNextCommandAndDoesNotReviveCompletedWork() {
        val machine = composition()
        val persistence = invocationPersistence(machine)
        val restored = persistence.restore(persistence.save(opened()))
        val child = assertIs<ChildCheckpoint.Active<Int, String>>(restored.child)
        val first =
            child.checkpoint.lanes.running.keys
                .single()
        val decision = machine.decide(restored.snapshot, InvocationInput.Child(child.owner, MachineInput.CommandCompleted(first, restored.parent.now)))
        assertIs<DecisionOutcome.Handled>(decision.outcome)
        val promoted = child.checkpoint.lanes.finished(first)
        assertEquals("second", promoted.started.single().command)
        val childSnapshot = assertIs<ChildInvocation.Active<Int>>(decision.snapshot.child)
        val cp = restored.copy(child = ChildCheckpoint.Active(child.owner, child.checkpoint.copy(snapshot = childSnapshot.snapshot, lanes = promoted.lanes)))
        val again = persistence.restore(persistence.save(cp))
        val remaining = assertIs<ChildCheckpoint.Active<Int, String>>(again.child)
        assertEquals(
            listOf("second"),
            remaining.checkpoint.registrations.values
                .map { it.command },
        )
        assertTrue(
            remaining.checkpoint.lanes.queued
                .isEmpty(),
        )
        val finished =
            machine.decide(
                again.snapshot,
                InvocationInput.Child(
                    remaining.owner,
                    MachineInput.CommandResult(
                        remaining.checkpoint.lanes.running.keys
                            .single(),
                        Finish,
                        again.parent.now,
                    ),
                ),
            )
        assertEquals(ChildInvocation.Dormant, finished.snapshot.child)
        assertIs<Parent.Idle>(finished.snapshot.parent.context)
    }

    @Test
    fun restoredChildTimerCompletesParentAndRetainsRemainingDelay() {
        val persistence = invocationPersistence(composition())
        val restored = persistence.restore(persistence.save(opened()))
        val child = assertIs<ChildCheckpoint.Active<Int, String>>(restored.child)
        val timer =
            child.checkpoint.snapshot.timers.keys
                .single()
        assertTrue(child.checkpoint.remaining(timer) { assertEquals(6.seconds, it) })
        val result = composition().decide(restored.snapshot, InvocationInput.Child(child.owner, MachineInput.TimerFired(timer, MachineTime(10.seconds))))
        assertIs<DecisionOutcome.Handled>(result.outcome)
        assertIs<Parent.Idle>(result.snapshot.parent.context)
        assertEquals(ChildInvocation.Dormant, result.snapshot.child)
        assertEquals(ChildCheckpoint.Dormant, persistence.restore(persistence.save(checkpoint(result, MachineTime(10.seconds)))).child)
    }

    @Test
    fun rejectsCorruptionVersionsOwnerMismatchAndUnsafeCheckpoints() {
        val persistence = invocationPersistence(composition())
        val active = opened()
        val child = assertIs<ChildCheckpoint.Active<Int, String>>(active.child)
        val saved = persistence.save(active)
        assertFailsWith<IllegalArgumentException> { persistence.restore("broken") }
        assertFailsWith<IllegalArgumentException> { invocationPersistence(composition(parentVersion = "2")).restore(saved) }
        assertFailsWith<IllegalArgumentException> { invocationPersistence(composition(childVersion = "2")).restore(saved) }

        fun changed(
            key: String,
            value: JsonElement,
        ): String = JsonObject(Json.parseToJsonElement(saved).jsonObject + (key to value)).toString()
        assertFailsWith<IllegalArgumentException> { persistence.restore(changed("format", JsonPrimitive(99))) }
        assertFailsWith<IllegalArgumentException> { persistence.restore(changed("ownerState", JsonPrimitive("other"))) }
        assertFailsWith<IllegalArgumentException> { persistence.restore(changed("childDefinition", JsonPrimitive("other"))) }
        val badChild =
            JsonObject(
                Json
                    .parseToJsonElement(saved)
                    .jsonObject
                    .getValue("child")
                    .jsonObject + ("owner" to JsonPrimitive(999)),
            )
        assertFailsWith<IllegalArgumentException> { persistence.restore(changed("child", badChild)) }
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(child = ChildCheckpoint.Dormant)) }
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(child = child.copy(owner = ActivationId(999)))) }
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(child = child.copy(checkpoint = child.checkpoint.copy(now = MachineTime.Zero)))) }
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(child = child.copy(checkpoint = child.checkpoint.copy(lanes = Lanes(), ending = child.checkpoint.registrations)))) }
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(parent = active.parent.copy(effects = listOf(PendingEffect(EffectId(1), Finished, EffectPolicy.Retained(), 0, false))))) }
        val freshChild = ExecutorCheckpoint.initial<Int, String>(composition().child.initialSnapshot(7))
        assertFailsWith<IllegalArgumentException> { persistence.save(active.copy(child = child.copy(checkpoint = freshChild))) }
        val dormant = InvocationCheckpoint<Parent, String, Int, String>(ExecutorCheckpoint.initial(composition().parent.initialSnapshot(Parent.Idle())))
        assertFailsWith<IllegalArgumentException> { invocationPersistence(composition(childVersion = "2")).restore(persistence.save(dormant)) }
    }

    @Test
    fun groupRestoresInvocationAsOneMemberBeforeApplyingWriters() {
        val original = opened()
        var writes = 0
        val group =
            checkpointGroup({ original }) {
                invocation(invocationPersistence(composition()), read = { it }, write = { _, checkpoint ->
                    writes++
                    checkpoint
                })
            }
        val saved = group.save(original)
        assertEquals(original, group.restore(saved))
        assertEquals(1, writes)
        val document = Json.parseToJsonElement(saved).jsonObject
        val members = document.getValue("machines").jsonObject
        val member =
            Json
                .parseToJsonElement(
                    members
                        .getValue("parent")
                        .jsonArray
                        .single()
                        .jsonPrimitive.content,
                ).jsonObject
        val invalidMember = JsonObject(member + ("ownerState" to JsonPrimitive("other")))
        val invalidMembers = JsonObject(members + ("parent" to JsonArray(listOf(JsonPrimitive(invalidMember.toString())))))
        assertFailsWith<IllegalArgumentException> { group.restore(JsonObject(document + ("machines" to invalidMembers)).toString()) }
        assertEquals(1, writes)
        for (values in listOf(emptyList(), listOf("broken", "broken"))) {
            val invalid = JsonObject(document + ("machines" to JsonObject(members + ("parent" to JsonArray(values.map(::JsonPrimitive)))))).toString()
            val error = assertFailsWith<IllegalArgumentException> { group.restore(invalid) }
            assertEquals("Expected one invocation checkpoint for parent", error.message)
            assertEquals(1, writes)
        }
        assertFailsWith<IllegalArgumentException> {
            checkpointGroup({ original }) {
                invocation(invocationPersistence(composition()), read = { it }, write = { _, cp -> cp })
                invocation(invocationPersistence(composition()), read = { it }, write = { _, cp -> cp })
            }
        }
    }

    private fun tracedPersistence(calls: MutableList<String>) =
        InvocationPersistence(
            composition(),
            RecordingCodec(Parent.serializer().recordPersistenceCalls("parent", calls), Act.serializer(), String.serializer(), Finished.serializer()),
            RecordingCodec(Int.serializer().recordPersistenceCalls("child", calls), Finish.serializer(), String.serializer(), Finished.serializer()),
        )

    @Test
    fun childPayloadIsEncodedAndDecodedBeforeParentPayload() {
        val calls = mutableListOf<String>()
        val persistence = tracedPersistence(calls)
        val original = opened()

        val saved = persistence.save(original)
        assertEquals(listOf("encode:child", "encode:parent"), calls)
        calls.clear()
        assertEquals(original, persistence.restore(saved))
        assertEquals(listOf("decode:child", "decode:parent"), calls)
    }

    @Test
    fun compositeValidationRunsBeforeAnyApplicationSerializer() {
        val calls = mutableListOf<String>()
        val original = opened()
        val child = assertIs<ChildCheckpoint.Active<Int, String>>(original.child)

        val error =
            assertFailsWith<IllegalArgumentException> {
                tracedPersistence(calls).save(original.copy(child = child.copy(owner = ActivationId(999))))
            }

        assertEquals("Invocation child does not match parent activation", error.message)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun documentIdentityIsCheckedBeforeReadingEitherPayload() {
        val calls = mutableListOf<String>()
        val persistence = tracedPersistence(calls)
        val document = Json.parseToJsonElement(persistence.save(opened())).jsonObject
        val child = document.getValue("child").jsonObject
        val corrupted =
            JsonObject(
                document +
                    mapOf(
                        "ownerState" to JsonPrimitive("other"),
                        "parent" to JsonPrimitive("broken"),
                        "child" to JsonObject(child + ("checkpoint" to JsonPrimitive("broken"))),
                    ),
            ).toString()
        calls.clear()

        val error = assertFailsWith<IllegalArgumentException> { persistence.restore(corrupted) }

        assertEquals("Invocation owner state differs", error.message)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun childPayloadFailureDoesNotReadParentPayload() {
        val calls = mutableListOf<String>()
        val persistence = tracedPersistence(calls)
        val document = Json.parseToJsonElement(persistence.save(opened())).jsonObject
        val child = document.getValue("child").jsonObject
        val corrupted = JsonObject(document + ("child" to JsonObject(child + ("checkpoint" to JsonPrimitive("{"))))).toString()
        calls.clear()

        val error = assertFailsWith<IllegalArgumentException> { persistence.restore(corrupted) }

        assertTrue(error.message.orEmpty().startsWith("not JSON: "))
        assertNull(error.cause)
        assertEquals(emptyList(), calls)
    }
}
