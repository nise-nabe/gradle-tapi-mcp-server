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

    fun rejectUnscopedMultiProject(subprojectCount: Int? = null): Nothing {
        val countPhrase = subprojectCount?.let { "($it subprojects)" } ?: "(multiple subprojects)"
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "testClasses/testMethods without taskPath or tasks run matching tests in every subproject " +
                "$countPhrase. Specify taskPath (e.g. \":module:test\") or tasks to scope execution.",
        )
    }

    fun validateProjectScope(options: TestRunOptions, project: GradleProject) {
        if (!requiresProjectScopeCheck(options)) {
            return
        }
        if (project.children.isEmpty()) {
            return
        }
        rejectUnscopedMultiProject(countGradleSubprojects(project))
    }

    private fun countGradleSubprojects(project: GradleProject): Int =
        project.children.sumOf { child -> 1 + countGradleSubprojects(child) }
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
    if (runtime.connectionManager.cachedHasSubprojects(projectDirectory) == true) {
        TestRunPreflight.rejectUnscopedMultiProject()
    }
    if (deferScopeModelCheck) {
        return
    }
    ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = runtime.buildExecutionManager,
        message = { dir ->
            "A Gradle build is already running for ${dir.path}. " +
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
    if (connectionManager.cachedHasSubprojects(projectDirectory) == true) {
        TestRunPreflight.rejectUnscopedMultiProject()
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
