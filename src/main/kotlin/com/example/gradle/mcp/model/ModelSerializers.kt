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
        walkGradleProjectTree(
            project = project,
            treeOptions = options,
            depth = depth,
            enrichNode = { _, _ -> },
            recurseChild = { child, childDepth -> projectOverview(child, options, childDepth) },
        )

    fun gradleProject(
        project: GradleProject,
        options: ModelQueryOptions = ModelQueryOptions(),
        treeOptions: ProjectTreeOptions = ProjectTreeOptions(),
        depth: Int = 0,
    ): Map<String, Any?> {
        val taskBudget = TaskFilterBudget(options.maxTasks)
        fun walk(p: GradleProject, d: Int): Map<String, Any?> =
            walkGradleProjectTree(
                project = p,
                treeOptions = treeOptions,
                depth = d,
                enrichNode = { node, result ->
                    result["tasks"] = taskBudget.takeAndSerialize(
                        filterTasksWithoutLimit(node.tasks.map(::taskSnapshot), options),
                    ) { serializeTask(it, options.includeTaskDetails) }
                },
                recurseChild = { child, childDepth -> walk(child, childDepth) },
                rootMetadata = { taskBudget.rootMetadata() },
            )
        return walk(project, depth)
    }

    fun buildInvocations(
        invocations: BuildInvocations,
        project: GradleProject,
        options: ModelQueryOptions = ModelQueryOptions(),
        treeOptions: ProjectTreeOptions = ProjectTreeOptions(),
    ): Map<String, Any?> {
        val taskBudget = TaskFilterBudget(options.maxTasks)
        val tasks = collectSerializedTasksWithGlobalBudget(project, treeOptions, options, taskBudget)
        return buildMap {
            put("tasks", tasks)
            putAll(taskBudget.rootMetadata())
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
        filterTasks(tasks, options).map { serializeTask(it, options.includeTaskDetails) }

    private fun collectSerializedTasksWithGlobalBudget(
        project: GradleProject,
        treeOptions: ProjectTreeOptions,
        options: ModelQueryOptions,
        budget: TaskFilterBudget,
        depth: Int = 0,
    ): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        result.addAll(
            budget.takeAndSerialize(
                filterTasksWithoutLimit(project.tasks.map(::taskSnapshot), options),
            ) { serializeTask(it, options.includeTaskDetails) },
        )

        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth, treeOptions.maxDepth, project.children.size)
        if (depthLimit.omitChildren) {
            return result
        }

        val allChildren = project.children.toList()
        val childLimit = ProjectTreeLimits.applyChildLimit(allChildren.size, treeOptions.maxChildren)
        allChildren.take(childLimit.visibleChildCount).forEach { child ->
            result.addAll(collectSerializedTasksWithGlobalBudget(child, treeOptions, options, budget, depth + 1))
        }
        return result
    }

    private fun walkGradleProjectTree(
        project: GradleProject,
        treeOptions: ProjectTreeOptions,
        depth: Int,
        enrichNode: (GradleProject, MutableMap<String, Any?>) -> Unit,
        recurseChild: (GradleProject, Int) -> Map<String, Any?>,
        rootMetadata: () -> Map<String, Any?> = { emptyMap() },
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>(
            "name" to project.name,
            "path" to project.path,
            "description" to project.description,
            "buildDirectory" to project.buildDirectory?.absolutePath,
            "projectDirectory" to project.projectDirectory.absolutePath,
            "taskCount" to project.tasks.size,
        )
        enrichNode(project, result)

        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth, treeOptions.maxDepth, project.children.size)
        if (depthLimit.omitChildren) {
            if (depthLimit.truncated) {
                result["truncated"] = true
                result["totalChildCount"] = depthLimit.totalChildCount
            }
            result["children"] = emptyList<Map<String, Any?>>()
            return if (depth == 0) {
                result + rootMetadata()
            } else {
                result
            }
        }

        val allChildren = project.children.toList()
        val childLimit = ProjectTreeLimits.applyChildLimit(allChildren.size, treeOptions.maxChildren)
        val childrenToSerialize = allChildren.take(childLimit.visibleChildCount)

        result["children"] = childrenToSerialize.map { child ->
            recurseChild(child, depth + 1)
        }

        if (childLimit.truncated) {
            result["truncated"] = true
            result["totalChildCount"] = childLimit.totalChildCount
        }

        return if (depth == 0) {
            result + rootMetadata()
        } else {
            result
        }
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

    private class TaskFilterBudget(private val maxTasks: Int?) {
        private var remaining: Int? = maxTasks
        private var totalMatched: Int = 0
        private var totalEmitted: Int = 0

        fun <T> takeAndSerialize(items: List<T>, serialize: (T) -> Map<String, Any?>): List<Map<String, Any?>> {
            totalMatched += items.size
            val max = remaining
            val taken = when {
                max == null -> items
                max <= 0 -> emptyList()
                else -> items.take(max).also { remaining = max - it.size }
            }
            val serialized = taken.map(serialize)
            totalEmitted += serialized.size
            return serialized
        }

        fun rootMetadata(): Map<String, Any?> =
            if (maxTasks != null && totalMatched > totalEmitted) {
                mapOf(
                    "tasksTruncated" to true,
                    "tasksTotalMatched" to totalMatched,
                )
            } else {
                emptyMap()
            }
    }
}
