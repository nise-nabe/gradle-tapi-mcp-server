package com.example.gradle.mcp.model

import com.example.gradle.mcp.connection.buildEnvironmentSnapshotFrom
import com.example.gradle.mcp.connection.toMap
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.Help
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.gradle.ProjectPublications

object ModelSerializers {
    fun buildEnvironment(environment: BuildEnvironment): Map<String, Any?> =
        buildEnvironmentSnapshotFrom(environment).toMap()

    fun help(help: Help, options: HelpLimitOptions = HelpLimitOptions()): Map<String, Any?> =
        OutputLimiter.limitFields(help.renderedText, options.toOutputLimitOptions(), "renderedText")

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
    ): Map<String, Any?> =
        gradleProjectWithBudget(project, options, treeOptions, depth, TaskFilterBudget(options))

    internal fun gradleProjectWithBudget(
        project: GradleProject,
        options: ModelQueryOptions,
        treeOptions: ProjectTreeOptions,
        depth: Int,
        taskBudget: TaskFilterBudget,
    ): Map<String, Any?> {
        val node = projectNode(project, treeOptions, options, depth, includeTasks = true, taskBudget)
        val result = node + mapOf(
            "tasks" to taskBudget.filterAndSerialize(project.tasks.map(::taskSnapshot)),
        )
        return if (depth == 0) {
            result + taskBudget.rootMetadata()
        } else {
            result
        }
    }

    fun buildInvocations(
        invocations: BuildInvocations,
        project: GradleProject,
        options: ModelQueryOptions = ModelQueryOptions(),
        treeOptions: ProjectTreeOptions = ProjectTreeOptions(),
    ): Map<String, Any?> =
        buildMap {
            put(
                "tasks",
                serializeTasks(
                    collectTasksFromProjectTree(project, treeOptions),
                    options.copy(includeTasks = true),
                ),
            )
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

    fun gradleBuild(
        build: GradleBuild,
        treeOptions: ProjectTreeOptions = ProjectTreeOptions(),
        visitedBuilds: MutableSet<String> = mutableSetOf(),
    ): Map<String, Any?> {
        val buildRootDir = build.buildIdentifier.rootDir.absolutePath
        if (buildRootDir in visitedBuilds) {
            return mapOf(
                "buildRootDir" to buildRootDir,
                "cycleReference" to true,
            )
        }
        visitedBuilds.add(buildRootDir)
        val projects = build.projects.toList()

        return buildMap {
            put("buildRootDir", buildRootDir)
            put("rootProject", basicGradleProjectNode(build.rootProject, treeOptions, depth = 0))
            put("projectCount", projects.size)
            put("projects", projects.map(::basicGradleProjectSummary))
            put(
                "includedBuilds",
                build.includedBuilds.map { included -> gradleBuild(included, treeOptions, visitedBuilds) },
            )
            put(
                "editableBuilds",
                build.editableBuilds.map { editable -> gradleBuild(editable, treeOptions, visitedBuilds) },
            )
        }
    }

    internal fun basicGradleProjectNode(
        project: BasicGradleProject,
        treeOptions: ProjectTreeOptions,
        depth: Int,
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>(
            "name" to project.name,
            "path" to project.path,
            "projectDirectory" to project.projectDirectory.absolutePath,
        )
        project.buildTreePath?.takeIf { it.isNotEmpty() }?.let { result["buildTreePath"] = it }

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
            basicGradleProjectNode(child, treeOptions, depth + 1)
        }

        if (childLimit.truncated) {
            result["truncated"] = true
            result["totalChildCount"] = childLimit.totalChildCount
        }

        return result
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

    internal fun collectTasksFromProjectTree(
        project: GradleProject,
        treeOptions: ProjectTreeOptions,
        depth: Int = 0,
    ): List<TaskSnapshot> {
        val tasks = mutableListOf<TaskSnapshot>()
        tasks.addAll(project.tasks.map(::taskSnapshot))

        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth, treeOptions.maxDepth, project.children.size)
        if (depthLimit.omitChildren) {
            return tasks
        }

        val allChildren = project.children.toList()
        val childLimit = ProjectTreeLimits.applyChildLimit(allChildren.size, treeOptions.maxChildren)
        allChildren.take(childLimit.visibleChildCount).forEach { child ->
            tasks.addAll(collectTasksFromProjectTree(child, treeOptions, depth + 1))
        }
        return tasks
    }

    fun filterTasks(tasks: List<TaskSnapshot>, options: ModelQueryOptions): List<TaskSnapshot> {
        if (!options.includeTasks) {
            return emptyList()
        }
        return filterTasksWithoutLimit(tasks, options).let { filtered ->
            options.maxTasks?.let { max -> filtered.take(max) } ?: filtered
        }
    }

    internal fun filterTasksWithoutLimit(tasks: List<TaskSnapshot>, options: ModelQueryOptions): List<TaskSnapshot> {
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
        return filtered.toList()
    }

    fun serializeTasks(tasks: List<TaskSnapshot>, options: ModelQueryOptions): List<Map<String, Any?>> =
        filterTasks(tasks, options).map { serializeTaskSnapshot(it, options.includeTaskDetails) }

    internal fun serializeTaskSnapshot(task: TaskSnapshot, includeDetails: Boolean): Map<String, Any?> =
        serializeTask(task, includeDetails)

    private fun projectNode(
        project: GradleProject,
        treeOptions: ProjectTreeOptions,
        modelOptions: ModelQueryOptions,
        depth: Int,
        includeTasks: Boolean,
        taskBudget: TaskFilterBudget = TaskFilterBudget(modelOptions),
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
                gradleProjectWithBudget(child, modelOptions, treeOptions, depth + 1, taskBudget)
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

    private fun basicGradleProjectSummary(project: BasicGradleProject): Map<String, Any?> =
        buildMap {
            put("name", project.name)
            put("path", project.path)
            put("projectDirectory", project.projectDirectory.absolutePath)
            project.buildTreePath?.takeIf { it.isNotEmpty() }?.let { put("buildTreePath", it) }
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
