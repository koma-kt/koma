package koma.buildlogic.dsl

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.getByType
import org.gradle.plugin.use.PluginDependency

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(name: String): MinimalExternalModuleDependency =
    findLibrary(name).get().get()

internal fun VersionCatalog.plugin(name: String) =
    findPlugin(name).get().get()

internal fun PluginManager.alias(plugin: PluginDependency) {
    apply(plugin.pluginId)
}
