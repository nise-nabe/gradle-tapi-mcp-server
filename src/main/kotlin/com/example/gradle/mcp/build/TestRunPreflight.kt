package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
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

    fun validateProjectScope(options: TestRunOptions, project: GradleProject) {
        if (!requiresProjectScopeCheck(options)) {
            return
        }
        if (project.children.isEmpty()) {
            return
        }
        val subprojectCount = countGradleSubprojects(project)
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "testClasses/testMethods without taskPath or tasks run matching tests in every subproject " +
                "($subprojectCount subprojects). Specify taskPath (e.g. \":module:test\") or tasks to scope execution.",
        )
    }

    private fun countGradleSubprojects(project: GradleProject): Int =
        project.children.sumOf { child -> 1 + countGradleSubprojects(child) }
}

context(runtime: GradleMcpRuntime)
internal fun preflightRunTests(projectDirectory: File, options: TestRunOptions) {
    if (!TestRunPreflight.requiresProjectScopeCheck(options)) {
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
        runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
            val project = connection.getModel(GradleProject::class.java)
            TestRunPreflight.validateProjectScope(options, project)
        }
    }
}
