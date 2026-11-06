package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionId
import actron.statechart.machine.check
import actron.statechart.machine.typedStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AdvancedTestingTest {
    @Test
    fun cancelledScheduleClosesEveryCommandBeforePropagatingCancellation() =
        runTest {
            val handlers = mutableListOf<ScriptedCommandHandler<Int, Act>>()
            val cancellation = CancellationException("stop exploration")
            val failure =
                assertFailsWith<CancellationException> {
                    machine().exploreSchedules(
                        Context.Idle,
                        this,
                        setup = {
                            send(Act.Load)
                            handlers += handler
                        },
                        choices = { throw cancellation },
                        check = {},
                    )
                }
            assertTrue(failure === cancellation)
            val handler = handlers.single()
            assertEquals(2, handler.started.size)
            assertEquals(handler.started.map { it.id }, handler.cancelled)
            assertTrue(handler.running.isEmpty())
        }

    sealed interface Context {
        val answers: Set<Int> get() = emptySet()

        data object Idle : Context

        data class Loading(
            override val answers: Set<Int> = emptySet(),
        ) : Context
    }

    sealed interface Act : Action {
        data object Load : Act

        data object Cancel : Act

        data class Answer(
            val id: Int,
        ) : Act

        data object Tick : Act
    }

    sealed interface Ev : Event

    private fun machine() =
        typedStateMachine<Context, Act, Int, Ev>(
            DefinitionId("advanced"),
            DefinitionVersion("1"),
            Context.Idle,
        ) {
            state<Context.Idle> { on<Act.Load, Context.Loading> { _, _ -> Context.Loading() } }
            state<Context.Loading> {
                onEnter {
                    command(1)
                    command(2)
                }
                internal<Act.Answer>(guard = { _, action -> action.id > 0 }) { ctx, action -> Context.Loading(answers = ctx.answers + action.id) }
                on<Act.Cancel, Context.Idle> { _, _ -> Context.Idle }
            }
        }

    private fun trace(vararg events: Pair<Long, Act>) =
        machine().scenarios(Context.Idle) {}.record(
            MachineScenario("temporal", listOf(MachineInput.Start(MachineTime.Zero)) + events.map { (time, action) -> MachineInput.Dispatch(action, MachineTime(time.milliseconds)) }),
        )

    private fun obligations() =
        TemporalRules<Context, Act, Int, Ev>().apply {
            respondsWithin(
                "load-finishes",
                10.milliseconds,
                5,
                triggered = { (it.input as? MachineInput.Dispatch)?.action == Act.Load },
                satisfied = { _, later -> later.after.context.answers.size == 2 || later.after.context == Context.Idle },
            )
        }

    @Test fun temporalRulesDistinguishPendingExpiredAndSatisfiedWithInclusiveTimeBoundary() {
        val rules = obligations()
        assertEquals(1, rules.check(trace(0L to Act.Load)).pending.size)
        assertFailsWith<AssertionError> { rules.check(trace(0L to Act.Load)).assertSatisfied() }
        rules.check(trace(0L to Act.Load, 10L to Act.Answer(1), 10L to Act.Answer(2))).assertSatisfied()
        val expired = rules.check(trace(0L to Act.Load, 11L to Act.Answer(1)))
        assertEquals(1, expired.violations.size)
        assertEquals(1, expired.violations.single().triggerStep)
        assertEquals(1, expired.triggers.getValue("load-finishes"))
        rules.check(trace(0L to Act.Load, 1L to Act.Cancel)).assertSatisfied()
    }

    @Test fun temporalRuleCorrelatesOverlappingObligationsAndRejectsStepBudget() {
        val rules =
            TemporalRules<Context, Act, Int, Ev>().apply {
                respondsWithin(
                    "each-answer",
                    20.milliseconds,
                    2,
                    triggered = { (it.input as? MachineInput.Dispatch)?.action is Act.Answer },
                    satisfied = { trigger, later -> later.input is MachineInput.Dispatch && later.input.action == Act.Cancel && trigger.after.context.answers.size == 1 },
                )
            }
        val result = rules.check(trace(0L to Act.Load, 1L to Act.Answer(1), 2L to Act.Answer(2), 3L to Act.Cancel, 4L to Act.Tick))
        assertEquals(2, result.triggers.getValue("each-answer"))
        assertEquals(1, result.violations.size)
        assertTrue(result.pending.isEmpty())
    }

    @Test fun temporalSafetyRunsInsideGeneratorAndRecording() {
        val rules =
            TemporalRules<Context, Act, Int, Ev>().apply {
                always("no-answers") {
                    it
                        .last()
                        .after.context.answers
                        .isEmpty()
                }
            }
        val model =
            machine().scenarios(Context.Idle) {
                actions(Act.Load, Act.Answer(1))
                temporal(rules)
            }
        val generated = model.generate(maxDepth = 3)
        assertTrue(generated.exploration.failures.isNotEmpty())
        val failed =
            model.record(
                MachineScenario(
                    "failure",
                    generated.exploration.failures
                        .first()
                        .inputs,
                ),
            )
        assertFailsWith<AssertionError> { failed.assertSuccess() }
    }

    @Test fun executorEnumeratesBothAnswerOrdersAndActuallyCancelsCommands() =
        runTest {
            val endings = mutableSetOf<List<Int>>()
            val report =
                machine().exploreSchedules(Context.Idle, this, setup = { send(Act.Load) }, choices = {
                    when {
                        snapshot.context == Context.Idle -> emptyList()
                        snapshot.context.answers.size == 2 -> listOf(ScheduleOperation.Send(Act.Cancel))
                        else -> runningCommands.filter { it.command !in snapshot.context.answers }.map { ScheduleOperation.Answer(it.id, Act.Answer(it.command)) }
                    }
                }, check = {
                    if (snapshot.context.answers.size == 2) endings += snapshot.context.answers.toList()
                    if (snapshot.context == Context.Idle) assertEquals(2, handler.cancelled.size)
                })
            report.assertSuccess()
            assertEquals(setOf(listOf(1, 2), listOf(2, 1)), endings)
            assertEquals(2, report.completed.size)
        }

    @Test fun executorReportsMissingBudgetAndCounterexample() =
        runTest {
            val choices: MachineTestDriver<Context, Act, Int, Ev>.(List<ScheduleOperation<Act>>) -> List<ScheduleOperation<Act>> = { listOf(ScheduleOperation.Send(Act.Load)) }
            val bounded = machine().exploreSchedules(Context.Idle, this, maxDepth = 0, choices = choices, check = {})
            assertTrue(bounded.truncated)
            assertFailsWith<AssertionError> { bounded.assertSuccess() }
            val failed = machine().exploreSchedules(Context.Idle, this, choices = choices, check = { assertEquals(Context.Idle, snapshot.context) })
            assertEquals(listOf(ScheduleOperation.Send(Act.Load)), failed.failures.single().operations)
        }

    @Test fun mutationTestingDetectsMissingTransitionAndReportsWeakSuite() =
        runTest {
            val machine = machine()
            val mutation = TransitionMutation(TransitionId(0), MutationKind.RemoveTransition)
            val strong =
                machine.testMutations(listOf(mutation)) { variant ->
                    val driver = MachineTestDriver(variant, Context.Idle, this)
                    try {
                        driver.start()
                        driver.send(Act.Load)
                        assertEquals(Context.Loading(), driver.snapshot.context)
                    } finally {
                        driver.close()
                    }
                }
            strong.assertAllKilled()
            assertEquals(MutationOutcome.Killed, strong.results.single().outcome)
            val weak = machine.testMutations(listOf(mutation)) { }
            assertEquals(1, weak.survivors.size)
            assertFailsWith<AssertionError> { weak.assertAllKilled() }
            var invoked = 0
            assertFailsWith<AssertionError> {
                machine.testMutations(listOf(mutation)) {
                    invoked++
                    throw AssertionError("baseline fails")
                }
            }
            assertEquals(1, invoked)
        }

    @Test fun mutationHarnessErrorIsNotCountedAsKilled() =
        runTest {
            val machine = machine()
            var runs = 0
            val result =
                machine.testMutations(listOf(TransitionMutation(TransitionId(0), MutationKind.RemoveTransition))) {
                    if (runs++ > 0) error("test infrastructure failed")
                }
            assertEquals(MutationOutcome.Error, result.results.single().outcome)
        }

    @Test fun temporalRulesRejectVacuityAndBackwardsTime() {
        val rules = obligations()
        assertFailsWith<AssertionError> { rules.check(trace(0L to Act.Tick)).assertSatisfied() }
        rules.check(trace(0L to Act.Tick)).assertSatisfied(requireTriggers = false)
        val steps = trace(0L to Act.Load, 1L to Act.Cancel).steps
        assertFailsWith<IllegalArgumentException> { rules.check(steps.reversed()) }
    }

    @Test fun mutationTestsDetectMissingGuardAndMissingReducerSeparately() =
        runTest {
            val machine = machine()
            val candidates = machine.chart.mutationCandidates()
            val answerIndex =
                machine.chart.transitions.indexOfFirst {
                    (it.trigger as? actron.statechart.Trigger.OnAction)?.matcher?.matches(Act.Answer(1)) == true
                }
            val guard = candidates.single { it.kind == MutationKind.RemoveGuard && it.transition == TransitionId(answerIndex) }
            val effect = TransitionMutation(guard.transition, MutationKind.RemoveEffect)
            machine
                .testMutations(listOf(guard, effect)) { variant ->
                    val driver = MachineTestDriver(variant, Context.Idle, this)
                    try {
                        driver.start()
                        driver.send(Act.Load)
                        driver.send(Act.Answer(-1))
                        assertTrue(
                            driver.snapshot.context.answers
                                .isEmpty(),
                            "Negative answers are rejected",
                        )
                        driver.send(Act.Answer(1))
                        assertEquals(setOf(1), driver.snapshot.context.answers, "Accepted answers update context")
                    } finally {
                        driver.close()
                    }
                }.assertAllKilled()
        }

    @Test fun executorReportsRunBudgetAndUnconsumedWork() =
        runTest {
            val bounded =
                machine().exploreSchedules(
                    Context.Idle,
                    this,
                    maxRuns = 1,
                    choices = { listOf(ScheduleOperation.Send(Act.Load)) },
                    check = {},
                )
            assertTrue(bounded.truncated)
            assertFailsWith<AssertionError> { bounded.assertSuccess() }
            val incomplete =
                machine().exploreSchedules(
                    Context.Idle,
                    this,
                    setup = { send(Act.Load) },
                    choices = { emptyList() },
                    check = {},
                )
            assertTrue(incomplete.failures.isNotEmpty(), "A leaf with running commands cannot pass")
        }
}
