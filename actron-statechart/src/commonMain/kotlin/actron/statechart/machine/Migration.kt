package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.isConsistent

/** A semantic snapshot migration, independent of file-format codecs. */
class SnapshotMigration<From : Any, To : Any>(
    val from: DefinitionVersion,
    val to: DefinitionVersion,
    val transform: (MachineSnapshot<From>) -> MachineSnapshot<To>,
) {
    init {
        require(from != to) { "[Actron] A migration must change the version" }
    }
}

/** Checks restored data before it can allocate work or accept new inputs. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.validateSnapshot(snapshot: MachineSnapshot<C>): List<String> = validateSnapshotStructure(snapshot) + checkInvariantsForRestore(snapshot)

internal fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.validateSnapshotStructure(snapshot: MachineSnapshot<C>): List<String> =
    buildList {
        if (snapshot.definition != id) add("definition differs")
        if (snapshot.version != version) add("version differs")
        if (snapshot.revision < 0) add("negative revision")
        if (snapshot.isStarted) {
            if (!chart.isConsistent(snapshot.configuration)) add("inconsistent configuration/history")
            if (snapshot.activations.keys != snapshot.configuration.active) add("activations do not match active nodes")
            if (snapshot.activations.values
                    .toSet()
                    .size != snapshot.activations.size
            ) {
                add("duplicate activation ids")
            }
        } else if (listOf(snapshot.configuration.active, snapshot.configuration.history.keys, snapshot.activations.keys, snapshot.commands.keys, snapshot.timers.keys, snapshot.pendingEntries.keys).any { it.isNotEmpty() }) {
            add("unstarted snapshot contains active work")
        }
        if (snapshot.activations.values.any { it.value <= 0 || it.value > snapshot.counters.activations }) add("invalid activation counters")
        if (snapshot.commands.any { (id, record) -> id.value <= 0 || id.value > snapshot.counters.commands || record.scope !in snapshot.activations.values }) add("invalid command ids/scopes")
        val invalidEntries =
            snapshot.pendingEntries.any { (node, entry) ->
                node !in activities || snapshot.activations[node] != entry.activation || !snapshot.validEntryCommand(entry.command)
            }
        if (invalidEntries || snapshot.pendingEntries.values
                .map { it.command }
                .toSet()
                .size != snapshot.pendingEntries.size
        ) {
            add("invalid pending entries")
        }
        if (snapshot.timers.any { (id, record) ->
                id.value <= 0 || id.value > snapshot.counters.timers || record.transition.index !in chart.transitions.indices ||
                    chart.transitions[record.transition.index].let { transition -> !transition.isTimer || snapshot.activations[transition.source] != record.activation }
            }
        ) {
            add("invalid timers")
        }
        if (listOf(snapshot.counters.activations, snapshot.counters.commands, snapshot.counters.timers, snapshot.counters.effects).any { it < 0 }) add("negative counters")
    }

private fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.checkInvariantsForRestore(snapshot: MachineSnapshot<C>): List<String> = if (snapshot.definition == id && snapshot.version == version && snapshot.isStarted) checkInvariants(snapshot).map { "invariant ${it.name} failed" } else emptyList()

/** Applies one typed migration and validates the target's complete configuration, work and invariants. */
fun <From : Any, To : Any, A : Action, CMD : Any, E : Event> Machine<To, A, CMD, E>.migrateSnapshot(
    snapshot: MachineSnapshot<From>,
    migration: SnapshotMigration<From, To>,
): MachineSnapshot<To> {
    require(snapshot.definition == id && snapshot.version == migration.from && version == migration.to) { "[Actron] Migration endpoints do not match" }
    val migrated = migration.transform(snapshot)
    val issues = validateSnapshot(migrated)
    require(issues.isEmpty()) { "[Actron] Migrated snapshot is invalid: ${issues.joinToString()}" }
    return migrated
}

/** Version-chain migration for an unchanged context type; duplicate and cyclic routes are rejected. */
class SnapshotMigrations<C : Any>(
    migrations: List<SnapshotMigration<C, C>>,
) {
    private val routes = migrations.associateBy { it.from }

    init {
        require(routes.size == migrations.size) { "[Actron] Ambiguous migration routes" }
        for (start in routes.keys) {
            val visited = mutableSetOf<DefinitionVersion>()
            var current = start
            while (current in routes) {
                require(visited.add(current)) { "[Actron] Cyclic migration routes" }
                current = routes.getValue(current).to
            }
        }
    }

    /** Migrates every edge in order; callers still validate the final snapshot with the target machine. */
    fun migrate(
        snapshot: MachineSnapshot<C>,
        to: DefinitionVersion,
    ): MachineSnapshot<C> {
        var current = snapshot
        while (current.version != to) {
            val route = requireNotNull(routes[current.version]) { "[Actron] No migration from ${current.version} to $to" }
            val next = route.transform(current)
            require(next.definition == current.definition && next.version == route.to) { "[Actron] Migration returned wrong identity/version" }
            current = next
        }
        return current
    }
}

private fun MachineSnapshot<*>.validEntryCommand(command: CommandId): Boolean = command.value in 1..counters.commands && command !in commands
