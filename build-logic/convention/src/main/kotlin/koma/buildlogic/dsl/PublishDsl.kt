package koma.buildlogic.dsl

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project

internal fun Project.mavenPublishing(actions: MavenPublishBaseExtension.() -> Unit) = extensions.configure(MavenPublishBaseExtension::class.java, actions)
