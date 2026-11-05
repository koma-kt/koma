import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-platform`
    alias(libs.plugins.actron.publish)
}

publishConvention {
    artifactId.set("actron-bom")
}

// Publication coordinates are finalized by the shared convention after project evaluation.
// Include platform artifacts too, so Maven/JVM consumers can use explicit -jvm coordinates.
// Constraints align versions without adding runtime or debug dependencies to consumers.
gradle.projectsEvaluated {
    dependencies.constraints {
        rootProject.subprojects
            .filter { it != project && it.pluginManager.hasPlugin("actron.publish") }
            .forEach { library ->
                library.extensions
                    .getByType<PublishingExtension>()
                    .publications
                    .withType<MavenPublication>()
                    .forEach { publication ->
                        api("${publication.groupId}:${publication.artifactId}:${publication.version}")
                    }
            }
    }
}
