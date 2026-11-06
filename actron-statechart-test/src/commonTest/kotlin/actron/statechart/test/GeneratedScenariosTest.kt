package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TypedMachineStateBuilder
import actron.statechart.machine.typedStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GeneratedScenariosTest {
    sealed interface Context {
        val query: String
        val revision: Int
        val result: String get() = ""

        data class Idle(
            override val query: String = "",
            override val revision: Int = 0,
        ) : Context

        data class Waiting(
            override val query: String,
            override val revision: Int,
        ) : Context

        data class Loading(
            override val query: String,
            override val revision: Int,
        ) : Context

        data class Ready(
            override val query: String,
            override val revision: Int,
            override val result: String,
        ) : Context
    }

    sealed interface Act : Action {
        data class Edit(
            val query: String,
        ) : Act

        data class Answer(
            val revision: Int,
            val value: String,
        ) : Act

        data object Undeclared : Act
    }

    data class Fetch(
        val query: String,
        val revision: Int,
    )

    sealed interface Ev : Event

    private fun machine(brokenGuard: Boolean = false): Machine<Context, Act, Fetch, Ev> =
        typedStateMachine(
            DefinitionId("generated"),
            DefinitionVersion("1"),
            Context.Idle(),
        ) {
            fun TypedMachineStateBuilder<Context, Context, Act, Fetch, Ev>.edits() {
                on<Act.Edit> { target { action -> Context.Waiting(action.query, state.revision + 1) } }
            }
            state<Context.Idle> { edits() }
            state<Context.Waiting> {
                edits()
                after(delay = 5.milliseconds) { target { Context.Loading(state.query, state.revision) } }
            }
            state<Context.Loading> {
                edits()
                onEnter { command(Fetch(state.query, state.revision)) }
                on<Act.Answer, Context.Ready>(
                    guard = { context, action -> brokenGuard || action.revision == context.revision },
                ) { context, action -> Context.Ready(context.query, context.revision, action.value) }
                after(delay = 20.milliseconds) { target { Context.Idle(state.query, state.revision) } }
            }
            state<Context.Ready> { edits() }
        }

    private fun model(broken: Boolean = false) =
        machine(broken).scenarios(Context.Idle()) {
            actions { state -> if (state.context.revision < 2) listOf(Act.Edit("A"), Act.Edit("B"), Act.Undeclared) else emptyList() }
            replies(ReplyMode.entries.toSet()) { listOf(Act.Answer(it.revision, it.query)) }
            rule("stale-does-not-change-context") { step ->
                val input = step.input
                val action =
                    when (input) {
                        is MachineInput.Dispatch -> input.action
                        is MachineInput.CommandResult -> input.action
                        else -> return@rule true
                    }
                action !is Act.Answer || action.revision == step.before.context.revision || step.before.context == step.after.context
            }
            goal("late-A-after-B") { steps ->
                val step = steps.last()
                val input = step.input
                if (input !is MachineInput.Dispatch) {
                    false
                } else {
                    val action = input.action
                    action is Act.Answer && action.value == "A" && step.before.context.query == "B" && step.before.context is Context.Loading
                }
            }
        }

    @Test fun generatesRealCommandsTimersAndLateAnswersWithoutMergingEqualActiveNodes() {
        val model = model()
        val report = model.generate(maxDepth = 5, maxDecisions = 20_000)
        report.assertSuccess()
        assertTrue(report.scenarios.flatMap { it.inputs }.none { it is MachineInput.Dispatch && it.action == Act.Undeclared })
        assertTrue(
            report.exploration.coverage.timers.covered
                .isNotEmpty(),
        )
        val merged = report.scenarios.map { model.record(it).also { trace -> trace.assertSuccess() }.coverage }.reduce { a, b -> a.merge(b) }
        merged.assertTransitionsCovered()
        val trace = model.record(report.witnesses.getValue("late-A-after-B"))
        assertEquals(listOf("A", "B"), trace.steps.flatMap { it.explained.decision.commands }.map { it.command.query })
        assertEquals(trace.steps.last().before, trace.steps.last().after)
        assertIs<DecisionOutcome.Ignored>(
            trace.steps
                .last()
                .explained.decision.outcome,
        )
        val again = model.generate(maxDepth = 5, maxDecisions = 20_000)
        assertEquals(report.scenarios, again.scenarios)
        assertEquals(report.witnesses, again.witnesses)
    }

    @Test fun automaticallyFindsAndReproducesBrokenGuardWithExactFailurePrefix() {
        val model = model(broken = true)
        val report = model.generate(maxDepth = 5, maxDecisions = 20_000)
        assertFailsWith<AssertionError> { report.assertSuccess() }
        val failure = report.exploration.failures.single()
        assertTrue("invariant:stale-does-not-change-context:false" in failure.identities)
        val trace = model.record(MachineScenario("regression", failure.inputs))
        assertEquals(failure.identities, trace.failures.single().identities)
        assertEquals(failure.snapshot, trace.steps.last().after)
        assertEquals(failure.inputs, trace.steps.map { it.input })
        assertFailsWith<AssertionError> { trace.assertSuccess() }
    }

    @Test fun budgetsMissingDataAndUnreachableGoalsCannotReportSuccess() {
        val shallow = model().generate(maxDepth = 1)
        assertFalse(shallow.exploration.truncated)
        assertTrue(shallow.missingGoals.isNotEmpty())
        assertFailsWith<AssertionError> { shallow.assertSuccess() }
        val exhausted = model().generate(maxDecisions = 1)
        assertTrue(exhausted.exploration.truncated)
        assertFailsWith<AssertionError> { exhausted.assertSuccess() }
        val noReplies = machine().scenarios(Context.Idle()) { actions(Act.Edit("A")) }.generate(maxDepth = 3)
        assertTrue(
            noReplies.requirements
                .missing(noReplies.exploration.coverage)
                .transitions
                .isNotEmpty(),
        )
        assertFailsWith<AssertionError> { noReplies.assertSuccess() }
        val impossible =
            machine()
                .scenarios(Context.Idle()) { goal("impossible") { false } }
                .generate(CoverageTarget.Custom(CoverageRequirements()), maxDepth = 2)
        assertEquals(setOf("impossible"), impossible.missingGoals)
        assertFailsWith<AssertionError> { impossible.assertSuccess() }
    }

    @Test fun rulesAreCheckedOnStartAndIgnoredInputsAndThrownAssertionsAreReported() {
        val start = machine().scenarios(Context.Idle()) { rule("initial") { it.after.context.query == "missing" } }.generate()
        assertEquals(
            1,
            start.exploration.failures
                .single()
                .inputs.size,
        )
        val ignored =
            machine()
                .scenarios(Context.Idle()) {
                    inputs { _, now, _ -> listOf(MachineInput.Dispatch(Act.Undeclared, now)) }
                    rule("ignored") { step ->
                        if (step.input is MachineInput.Dispatch) throw AssertionError("failed assertion")
                        true
                    }
                }.generate()
        assertTrue(
            ignored.exploration.failures
                .single()
                .identities
                .any { it.startsWith("invariant:ignored:") },
        )
    }

    @Test fun invalidConfigurationAndBackwardsTimeFailBeforeBeingAccepted() {
        assertFailsWith<IllegalArgumentException> {
            machine().scenarios(Context.Idle()) {
                rule("same") { true }
                rule("same") { true }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            machine().scenarios(Context.Idle()) {
                goal("same") { true }
                goal("same") { true }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            machine().scenarios(Context.Idle()) { inputs { _, _, _ -> listOf(MachineInput.Dispatch(Act.Edit("A"), MachineTime((-1).milliseconds))) } }.generate()
        }
        assertFailsWith<IllegalArgumentException> { model().record(MachineScenario("no-start", emptyList())) }
    }
}
