package com.example.gradle.mcp.build

import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task

internal object TestTaskDiscovery {
    const val MULTI_PROJECT_TEST_SCOPE_HINT =
        "Pass taskPath (e.g. \":module:test\"), or use tasks: [\":mod:test\", \":mod:fastTest\"] " +
            "for custom JvmTestSuite suite names. Auto taskPath inference matches package suffix " +
            "tokens to subproject path segments and rejects ambiguous ties."

    fun collectJvmTestTaskPaths(project: GradleProject): List<String> =
        collectJvmTestTasks(project)
            .map { it.taskPath }
            .sortedWith(::compareGradleTaskPathsNaturally)

    fun inferTaskPath(project: GradleProject, classNames: List<String>): String? {
        val testTasks = collectJvmTestTasks(project)
        if (testTasks.isEmpty()) {
            return null
        }
        if (testTasks.size == 1) {
            return inferTaskPathForSingleTestTask(testTasks.single(), classNames)
        }
        if (classNames.isEmpty()) {
            return null
        }

        val tasksByProject = testTasks.groupBy { it.projectPath }
        val matchingProjectPaths = classNames.map { className ->
            bestMatchingProjectPath(tasksByProject.keys, className)
        }
        if (matchingProjectPaths.any { it == null }) {
            return null
        }
        val resolvedPaths = matchingProjectPaths.filterNotNull()
        val distinctMatches = resolvedPaths.distinct()
        if (distinctMatches.size != 1) {
            return null
        }

        val projectTasks = tasksByProject.getValue(distinctMatches.single())
        return projectTasks.singleOrNull()?.taskPath
    }

    internal fun isJvmTestTask(task: Task): Boolean {
        if (task.group != "verification") {
            return false
        }
        val name = task.name
        return name.equals("test", ignoreCase = true) || name.endsWith("Test")
    }

    internal fun compareGradleTaskPathsNaturally(left: String, right: String): Int {
        val leftParts = left.split(':').filter { it.isNotEmpty() }
        val rightParts = right.split(':').filter { it.isNotEmpty() }
        val sharedParts = minOf(leftParts.size, rightParts.size)
        for (index in 0 until sharedParts) {
            val comparison = comparePathSegmentsNaturally(leftParts[index], rightParts[index])
            if (comparison != 0) {
                return comparison
            }
        }
        return leftParts.size.compareTo(rightParts.size)
    }

    private data class JvmTestTaskRef(
        val projectPath: String,
        val taskPath: String,
    )

    private fun collectJvmTestTasks(project: GradleProject): List<JvmTestTaskRef> {
        val result = mutableListOf<JvmTestTaskRef>()
        collectJvmTestTasksRecursive(project, result)
        return result
    }

    private fun collectJvmTestTasksRecursive(project: GradleProject, result: MutableList<JvmTestTaskRef>) {
        project.tasks
            .filter(::isJvmTestTask)
            .forEach { task ->
                result.add(JvmTestTaskRef(projectPath = project.path, taskPath = task.path))
            }
        project.children.forEach { child ->
            collectJvmTestTasksRecursive(child, result)
        }
    }

    private fun inferTaskPathForSingleTestTask(task: JvmTestTaskRef, classNames: List<String>): String? {
        if (classNames.isEmpty()) {
            return null
        }
        val allMatch = classNames.all { className ->
            bestMatchingProjectPath(setOf(task.projectPath), className) == task.projectPath
        }
        return if (allMatch) task.taskPath else null
    }

    private fun bestMatchingProjectPath(projectPaths: Set<String>, className: String): String? {
        val scored = projectPaths
            .map { projectPath -> projectPath to matchScore(projectPath, className) }
            .filter { (_, score) -> score > 0 }
        if (scored.isEmpty()) {
            return null
        }
        val topScore = scored.maxOf { it.second }
        val topMatches = scored.filter { it.second == topScore }.map { it.first }
        return topMatches.singleOrNull()
    }

    private fun matchScore(projectPath: String, className: String): Int {
        val pathTokens = projectPathTokens(projectPath)
        val packageTokens = className.substringBeforeLast('.').split('.').map { it.lowercase() }
        var score = 0
        var pathIndex = pathTokens.lastIndex
        var packageIndex = packageTokens.lastIndex
        while (pathIndex >= 0 && packageIndex >= 0 && pathTokens[pathIndex] == packageTokens[packageIndex]) {
            score++
            pathIndex--
            packageIndex--
        }
        return score
    }

    private fun comparePathSegmentsNaturally(left: String, right: String): Int {
        val leftTokens = tokenizePathSegment(left)
        val rightTokens = tokenizePathSegment(right)
        val sharedTokens = minOf(leftTokens.size, rightTokens.size)
        for (index in 0 until sharedTokens) {
            val comparison = compareNaturalTokens(leftTokens[index], rightTokens[index])
            if (comparison != 0) {
                return comparison
            }
        }
        return leftTokens.size.compareTo(rightTokens.size)
    }

    private fun tokenizePathSegment(segment: String): List<String> =
        NATURAL_SORT_TOKEN_PATTERN.findAll(segment).map { it.value.lowercase() }.toList()

    private fun compareNaturalTokens(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    private fun projectPathTokens(projectPath: String): List<String> =
        projectPath
            .trim(':')
            .split(':')
            .flatMap { segment -> segment.split('-', '_') }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }

    private val NATURAL_SORT_TOKEN_PATTERN = Regex("""\d+|\D+""")
}
