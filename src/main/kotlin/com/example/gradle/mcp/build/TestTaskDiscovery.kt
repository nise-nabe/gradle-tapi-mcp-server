package com.example.gradle.mcp.build

import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task

internal object TestTaskDiscovery {
    const val MULTI_PROJECT_TEST_SCOPE_HINT =
        "Pass taskPath (e.g. \":module:test\"), or use tasks: [\":mod:test\", \":mod:fastTest\"] " +
            "for custom JvmTestSuite suite names."

    fun collectJvmTestTaskPaths(project: GradleProject): List<String> =
        collectJvmTestTasks(project).map { it.taskPath }.sorted()

    fun inferTaskPath(project: GradleProject, classNames: List<String>): String? {
        val testTasks = collectJvmTestTasks(project)
        if (testTasks.isEmpty()) {
            return null
        }
        if (testTasks.size == 1) {
            return testTasks.single().taskPath
        }
        if (classNames.isEmpty()) {
            return null
        }

        val tasksByProject = testTasks.groupBy { it.projectPath }
        val matchingProjectPaths = classNames.map { className ->
            bestMatchingProjectPath(tasksByProject.keys, className)
        }
        val distinctMatches = matchingProjectPaths.filterNotNull().distinct()
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

    private fun projectPathTokens(projectPath: String): List<String> =
        projectPath
            .trim(':')
            .split(':')
            .flatMap { segment -> segment.split('-', '_') }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
}
