package org.gradle.mcp

import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.ProjectPublications

object ModelSerializers {
    fun buildEnvironment(environment: BuildEnvironment): Map<String, Any?> = mapOf(
        "gradle" to mapOf(
            "gradleVersion" to environment.gradle.gradleVersion,
            "gradleUserHome" to environment.gradle.gradleUserHome?.absolutePath,
        ),
        "java" to mapOf(
            "javaHome" to environment.java.javaHome?.absolutePath,
            "jvmArguments" to environment.java.jvmArguments,
        ),
    )

    fun gradleProject(project: GradleProject): Map<String, Any?> = mapOf(
        "name" to project.name,
        "path" to project.path,
        "description" to project.description,
        "buildDirectory" to project.buildDirectory?.absolutePath,
        "projectDirectory" to project.projectDirectory.absolutePath,
        "children" to project.children.map { gradleProject(it) },
        "tasks" to project.tasks.map { task ->
            mapOf(
                "name" to task.name,
                "path" to task.path,
                "description" to task.description,
                "group" to task.group,
                "displayName" to task.displayName,
            )
        },
    )

    fun buildInvocations(invocations: BuildInvocations): Map<String, Any?> = mapOf(
        "tasks" to invocations.tasks.map { task ->
            mapOf(
                "name" to task.name,
                "path" to task.path,
                "description" to task.description,
                "group" to task.group,
                "displayName" to task.displayName,
            )
        },
        "taskSelectors" to invocations.taskSelectors.map { selector ->
            mapOf(
                "name" to selector.name,
                "description" to selector.description,
                "displayName" to selector.displayName,
            )
        },
    )

    fun projectPublications(publications: ProjectPublications): Map<String, Any?> = mapOf(
        "project" to mapOf(
            "projectPath" to publications.projectIdentifier.projectPath,
            "buildRootDir" to publications.projectIdentifier.buildIdentifier.rootDir.absolutePath,
        ),
        "publications" to publications.publications.map { publication ->
            mapOf(
                "group" to publication.id.group,
                "name" to publication.id.name,
                "version" to publication.id.version,
            )
        },
    )
}
