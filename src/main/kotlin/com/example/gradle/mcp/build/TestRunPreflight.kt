package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.model.GradleProject
import java.io.File

internal object TestRunPreflight {
    const val MAX_SUGGESTED_TEST_TASK_PATHS = 20

    data class SuggestedTestTaskPaths(
        val paths: List<String>,
        val truncated: Boolean,
    )
    fun requiresProjectScopeCheck(options: TestRunOptions): Boolean {
        val unscoped = when (options.selection) {
            is TestRunSelection.Classes -> options.selection.taskPath.isNullOrBlank()
            is TestRunSelection.Methods -> options.selection.taskPath.isNullOrBlank()
            is TestRunSelection.Patterns, null -> false
        }
        return unscoped && options.tasks.isEmpty()
    }

    fun rejectUnscopedMultiProject(
        subprojectCount: Int? = null,
        project: GradleProject? = null,
    ): Nothing {
        val countPhrase = subprojectCount?.let { "($it subprojects)" } ?: "(multiple subprojects)"
        val errorDetails = buildMap<String, Any?> {
            project?.let { root ->
                val suggestion = collectSuggestedTestTaskPaths(root)
                if (suggestion.paths.isNotEmpty()) {
                    put("suggestedTaskPaths", suggestion.paths)
                    if (suggestion.truncated) {
                        put("suggestedTaskPathsTruncated", true)
                    }
                }
            }
            put(
                "hint",
                buildHint(project != null),
            )
        }
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "testClasses/testMethods without taskPath or tasks run matching tests in every subproject " +
                "$countPhrase. Specify taskPath (e.g. \":module:test\") or tasks to scope execution.",
            errorDetails = errorDetails,
        )
    }

    fun validateProjectScope(options: TestRunOptions, project: GradleProject) {
        if (!requiresProjectScopeCheck(options)) {
            return
        }
        if (project.children.isEmpty()) {
            return
        }
        rejectUnscopedMultiProject(countGradleSubprojects(project), project)
    }

    fun collectSuggestedTestTaskPaths(
        root: GradleProject,
        maxPaths: Int = MAX_SUGGESTED_TEST_TASK_PATHS,
    ): SuggestedTestTaskPaths {
        val paths = mutableListOf<String>()
        collectTestTaskPathsRecursive(root, paths)
        val sorted = paths.sorted()
        if (sorted.size <= maxPaths) {
            return SuggestedTestTaskPaths(sorted, truncated = false)
        }
        return SuggestedTestTaskPaths(sorted.take(maxPaths), truncated = true)
    }

    private fun buildHint(hasProjectModel: Boolean): String {
        val base =
            "Pass taskPath (e.g. \":module:test\") or use tasks for custom JvmTestSuite names " +
                "(e.g. \":mod:fastTest\")."
        if (!hasProjectModel) {
            return base
        }
        return "$base suggestedTaskPaths lists JVM Test task paths (name test or *Test). " +
            "When truncated, use gradle_get_project_model with includeTasks=true for the full list."
    }

    private fun isLikelyJvmTestTaskName(name: String): Boolean =
        name == "test" || name.endsWith("Test")

    private fun collectTestTaskPathsRecursive(project: GradleProject, out: MutableList<String>) {
        project.tasks.forEach { task ->
            if (task.group == "verification" && isLikelyJvmTestTaskName(task.name)) {
                out.add(task.path)
            }
        }
        project.children.toList().forEach { child ->
            collectTestTaskPathsRecursive(child, out)
        }
    }

    private fun countGradleSubprojects(project: GradleProject): Int =
        project.children.toList().sumOf { child -> 1 + countGradleSubprojects(child) }
}

context(runtime: GradleMcpRuntime)
internal fun preflightRunTests(
    projectDirectory: File,
    options: TestRunOptions,
    deferScopeModelCheck: Boolean = false,
) {
    if (!TestRunPreflight.requiresProjectScopeCheck(options)) {
        return
    }
    if (deferScopeModelCheck) {
        return
    }
    ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = runtime.buildExecutionManager,
        message = { dir ->
            "A Gradle build is already active for ${dir.path}. " +
                "Poll gradle_get_build_status with the active buildId, call gradle_cancel_build to stop it, " +
                "or wait for it to finish."
        },
    ) {
        ensureTestRunProjectScope(runtime.connectionManager, projectDirectory, options)
    }
}

internal fun ensureTestRunProjectScope(
    connectionManager: GradleConnectionManager,
    projectDirectory: File,
    options: TestRunOptions,
) {
    if (!TestRunPreflight.requiresProjectScopeCheck(options)) {
        return
    }
    connectionManager.withConnectionResult(projectDirectory) { connection ->
        val project = connection.getModel(GradleProject::class.java)
        if (project.children.isEmpty()) {
            return@withConnectionResult
        }
        connectionManager.cacheHasSubprojects(projectDirectory, hasSubprojects = true)
        TestRunPreflight.validateProjectScope(options, project)
    }
}
