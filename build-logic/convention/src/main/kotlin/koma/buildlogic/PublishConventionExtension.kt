package koma.buildlogic

import koma.buildlogic.dsl.mavenPublishing
import org.gradle.api.Project

open class PublishConventionExtension {
    @Suppress("MemberVisibilityCanBePrivate")
    var artifactId: String? = null

    internal fun applyToProject(project: Project) {
        project.mavenPublishing {
            coordinates(
                groupId = project.group.toString(),
                artifactId = artifactId
                    ?: error("`publishConvention.artifactId` don't configure in `${project.path}`."),
                version = project.version.toString(),
            )
        }
    }
}

