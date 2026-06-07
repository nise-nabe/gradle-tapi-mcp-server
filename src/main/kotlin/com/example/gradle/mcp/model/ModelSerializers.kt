package com.example.gradle.mcp.model

import com.example.gradle.mcp.connection.BuildEnvironmentSnapshot
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.ProjectPublications

data class TaskSnapshot(
    val name: String,
    val path: String,
    val description: String?,
    val group: String?,
    val displayName: String,
)

object ModelSerializers {
    fun buildEnvironment(environment: BuildEnvironment): Map<String, Any?> =
        BuildEnvironmentSnapshot.from(environment).toMap()

    fun projectOverview(
        project: GradleProject,
        options: ProjectTreeOptions = ProjectTreeOptions(),
        depth: Int = 0,
    ): Map<String, Any?> =
        projectNode(project, options, ModelQueryOptions(), depth, includeTasks = false)

    fun gradleProject(
        project: GradleProject,
        options: ModelQueryOptions = ModelQueryOptions(),
        treeOptions: ProjectTreeOptions = ProjectTreeOptions(),
        depth: Int = 0,
    ): Map<String, Any?> {
        val node = projectNode(project, treeOptions, options, depth, includeTasks = true)
        return node + mapOf("tasks" to serializeTasks(project.tasks.map(::taskSnapshot), options))
    }

    fun buildInvocations(invocations: BuildInvocations, options: ModelQueryOptions = ModelQueryOptions()): Map<String, Any?> =
        buildMap {
            put("tasks", serializeTasks(invocations.tasks.map(::taskSnapshot), options.copy(includeTasks = true)))
            if (options.includeTaskSelectors) {
                put(
                    "taskSelectors",
                    invocations.taskSelectors.map { selector ->
                        mapOf(
                            "name" to selector.name,
                            "description" to selector.description,
                            "displayName" to selector.displayName,
                        )
                    },
                )
            }
        }

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

    fun filterTasks(tasks: List<TaskSnapshot>, options: ModelQueryOptions): List<TaskSnapshot> {
        if (!options.includeTasks) {
            return emptyList()
        }

        var filtered = tasks.asSequence()
        options.taskGroup?.let { group ->
            filtered = filtered.filter { it.group == group }
        }
        options.taskNamePrefix?.let { prefix ->
            filtered = filtered.filter { it.name.startsWith(prefix) }
        }
        options.maxTasks?.let { max ->
            filtered = filtered.take(max)
        }
        return filtered.toList()
    }

    fun serializeTasks(tasks: List<TaskSnapshot>, options: ModelQueryOptions): List<Map<String, Any?>> =
        filterTasks(tasks, options).map { serializeTask(it, options.includeTaskDetails) }

    private fun projectNode(
        project: GradleProject,
        treeOptions: ProjectTreeOptions,
        modelOptions: ModelQueryOptions,
        depth: Int,
        includeTasks: Boolean,
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>(
            "name" to project.name,
            "path" to project.path,
            "description" to project.description,
            "buildDirectory" to project.buildDirectory?.absolutePath,
            "projectDirectory" to project.projectDirectory.absolutePath,
            "taskCount" to project.tasks.size,
        )

        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth, treeOptions.maxDepth, project.children.size)
        if (depthLimit.omitChildren) {
            if (depthLimit.truncated) {
                result["truncated"] = true
                result["totalChildCount"] = depthLimit.totalChildCount
            }
            result["children"] = emptyList<Map<String, Any?>>()
            return result
        }

        val allChildren = project.children.toList()
        val childLimit = ProjectTreeLimits.applyChildLimit(allChildren.size, treeOptions.maxChildren)
        val childrenToSerialize = allChildren.take(childLimit.visibleChildCount)

        result["children"] = childrenToSerialize.map { child ->
            if (includeTasks) {
                gradleProject(child, modelOptions, treeOptions, depth + 1)
            } else {
                projectOverview(child, treeOptions, depth + 1)
            }
        }

        if (childLimit.truncated) {
            result["truncated"] = true
            result["totalChildCount"] = childLimit.totalChildCount
        }

        return result
    }

    private fun serializeTask(task: TaskSnapshot, includeDetails: Boolean): Map<String, Any?> =
        if (includeDetails) {
            mapOf(
                "name" to task.name,
                "path" to task.path,
                "description" to task.description,
                "group" to task.group,
                "displayName" to task.displayName,
            )
        } else {
            mapOf(
                "name" to task.name,
                "path" to task.path,
                "group" to task.group,
            )
        }

    private fun taskSnapshot(task: Task): TaskSnapshot =
        TaskSnapshot(
            name = task.name,
            path = task.path,
            description = task.description,
            group = task.group,
            displayName = task.displayName,
        )
}
