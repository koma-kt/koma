package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.Trigger
import kotlin.reflect.KClass

/** Immutable context at rule evaluation. */
@ActronStoreDsl
class TransitionRuleScope<C : Any> internal constructor(
    val state: C,
)

/**
 * A declaration, not an action callback. Exactly one [target] is required. A guard runs before exit hooks.
 * The target reads the context after exit hooks and supplies the complete destination value.
 */
@ActronStoreDsl
class TransitionRuleBuilder<C : Any, S : Any, T : Any>
    @PublishedApi
    internal constructor(
        private val reference: (KClass<out S>) -> StateId,
        private val applyTarget: (C, S) -> C,
    ) {
        private val declarations = mutableSetOf<String>()
        private var condition: TransitionRuleScope<C>.(T) -> Boolean = { true }

        // A missing required declaration is a definition error, never a runtime fallback target.
        private var complete: () -> RuleDestination<C, S, T> = { throw IllegalArgumentException("Exactly one target is required") }

        fun guard(predicate: TransitionRuleScope<C>.(T) -> Boolean) {
            require(declarations.add("guard")) { "guard is declared twice" }
            condition = predicate
        }

        inline fun <reified Target : S> target(noinline factory: TransitionRuleScope<C>.(T) -> Target) {
            destination(type = Target::class, factory = factory)
        }

        @PublishedApi
        internal fun destination(
            type: KClass<out S>,
            factory: TransitionRuleScope<C>.(T) -> S,
        ) {
            require(declarations.add("target")) { "target is declared twice" }
            val id = reference(type)
            complete = { RuleDestination(id = id, type = type, factory = factory) }
        }

        @PublishedApi
        internal fun <A : Action, CMD : Any, E : Event> register(
            delegate: MachineStateBuilder<C, A, CMD, E>,
            trigger: Trigger,
            action: (Action) -> T,
        ) {
            val destination = complete()
            val guard = condition
            val write = applyTarget
            delegate.addDeclared(
                target = destination.id,
                trigger = trigger,
                rule =
                    DeclaredRule(
                        guarded = "guard" in declarations,
                        guard = { context, input -> guard(TransitionRuleScope(state = context), action(input)) },
                        reduce = { context, input ->
                            val scope = TransitionRuleScope(state = context)
                            val event = action(input)
                            val target = destination.factory(scope, event)
                            require(target::class == destination.type) { "target must return its declared concrete type" }
                            write(context, target)
                        },
                    ),
            )
        }
    }

private class RuleDestination<C : Any, S : Any, T : Any>(
    val id: StateId,
    val type: KClass<out S>,
    val factory: TransitionRuleScope<C>.(T) -> S,
)

internal class DeclaredRule<C : Any>(
    val guarded: Boolean,
    val guard: (C, Action) -> Boolean,
    val reduce: (C, Action) -> C,
)
