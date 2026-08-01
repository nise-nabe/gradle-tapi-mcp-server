package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.model.GradleProject
import java.io.File

internal object TestRunPreflight {
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
                val paths = collectSuggestedTestTaskPaths(root)
                if (paths.isNotEmpty()) {
                    put("suggestedTaskPaths", paths)
                }
            }
            put(
                "hint",
                "Pass taskPath (e.g. \":module:test\") or use tasks for custom JvmTestSuite names " +
                    "(e.g. \":mod:fastTest\"). suggestedTaskPaths lists verification-group tasks from the model.",
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

    fun collectSuggestedTestTaskPaths(root: GradleProject): List<String> {
        val paths = mutableListOf<String>()
        collectTestTaskPathsRecursive(root, paths)
        return paths.sorted()
    }

    private fun collectTestTaskPathsRecursive(project: GradleProject, out: MutableList<String>) {
        project.tasks.forEach { task ->
            if (task.group == "verification") {
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
