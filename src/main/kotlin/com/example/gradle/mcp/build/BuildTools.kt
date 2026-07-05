package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectDirectoryScope
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.optionalPositiveInt
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.testMethodsProperty
import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.optionalProjectDirectoryProperty
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.requiredStringList
import com.example.gradle.mcp.protocol.stringArrayProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope

internal fun progressProperties(): Map<String, Any> =
    mapOf(
        "includeProgress" to booleanProperty("Task/test progress. Default false."),
        "includeProblems" to booleanProperty("Gradle Problems API events. Default false."),
        "includeDownloads" to booleanProperty("Download progress. Default false."),
        "includeTestDetails" to booleanProperty(
            "Test metadata and failedTests. Default false; live progress events need includeProgress=true.",
        ),
    )

internal fun outputProperties(): Map<String, Any> =
    mapOf(
        "includeOutput" to booleanProperty("Stdout/stderr. Default false (outcome/summary only)."),
        "maxOutputChars" to integerProperty(
            "Max chars per stream when includeOutput=true (default ${OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS})",
        ),
        "tailOutput" to booleanProperty("Keep tail when truncating output. Default true."),
    )

internal fun listBuildsSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to optionalProjectDirectoryProperty(),
            "limit" to integerProperty(
                "Max builds, most recent first (default ${BuildExecutionManager.DEFAULT_LIST_BUILDS}, max ${BuildExecutionManager.MAX_LIST_BUILDS})",
            ),
        ),
    )

internal fun cancelBuildSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("buildId"),
        properties = mapOf(
            "buildId" to stringProperty("Build ID from background gradle_run_tasks or gradle_run_tests"),
            "projectDirectory" to optionalProjectDirectoryProperty(),
        ),
    )

internal fun buildStatusSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("buildId"),
        properties = progressProperties() + outputProperties() + mapOf(
            "buildId" to stringProperty("Build ID from background gradle_run_tasks or gradle_run_tests"),
            "projectDirectory" to optionalProjectDirectoryProperty(),
        ),
    )

internal fun runOutputSchema(
    required: List<String>,
    extraProperties: Map<String, Any>,
): Map<String, Any> =
    objectSchema(
        required = required,
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
        ) + extraProperties + progressProperties() + outputProperties() + mapOf(
            "arguments" to stringArrayProperty("Extra Gradle CLI args (no init scripts or @files)"),
            "jvmArguments" to stringArrayProperty("Extra JVM args for the build"),
        ),
    )

internal fun runTasksSchema(): Map<String, Any> =
    runOutputSchema(
        required = listOf("tasks"),
        extraProperties = mapOf(
            "tasks" to stringArrayProperty("Gradle task paths to execute"),
            "background" to booleanProperty("Return buildId immediately. Default false."),
        ),
    )

internal fun runTestsSchema(): Map<String, Any> =
    runOutputSchema(
        required = emptyList(),
        extraProperties = mapOf(
            "testClasses" to stringArrayProperty(
                "Fully qualified JVM test class names. Class.method entries (e.g. com.example.FooTest.testBar) are normalized to testMethods.",
            ),
            "testMethods" to testMethodsProperty(),
            "taskPath" to stringProperty("Test task path for withTaskAndTest*. Requires testClasses or testMethods."),
            "includePattern" to stringProperty("Single test include pattern (Gradle 7.6+). Requires tasks."),
            "includePatterns" to stringArrayProperty("Test include patterns (Gradle 7.6+). Requires tasks."),
            "tasks" to stringArrayProperty("Test task paths for TestLauncher.forTasks() (Gradle 7.6+)."),
            "background" to booleanProperty("Return buildId immediately. Default false."),
        ),
    )

context(runtime: GradleMcpRuntime)
fun Server.registerBuildTools(serverScope: CoroutineScope) {
    registerTool(
        serverScope,
        name = "gradle_list_builds",
        description = McpToolDescriptions.LIST_BUILDS,
        schema = listBuildsSchema(),
    ) { args ->
        val projectScope = ProjectDirectoryScope(runtime.connectionManager)
        val projectDirectory = ProjectDirectoryResolver.resolveOptionalHint(
            args,
            boundary = projectScope::requireWithinBoundary,
        )
        val limit = args.optionalPositiveInt("limit") ?: BuildExecutionManager.DEFAULT_LIST_BUILDS
        jsonResult(runtime.buildExecutionManager.listBuilds(projectDirectory, limit))
    }
    registerTool(
        serverScope,
        name = "gradle_cancel_build",
        description = McpToolDescriptions.CANCEL_BUILD,
        schema = cancelBuildSchema(),
    ) { args ->
        val projectScope = ProjectDirectoryScope(runtime.connectionManager)
        val projectDirectory = ProjectDirectoryResolver.resolveOptionalHint(
            args,
            boundary = projectScope::requireWithinBoundary,
        )
        jsonResult(
            runtime.buildExecutionManager.cancelBuild(
                args.requiredString("buildId"),
                projectDirectory,
            ),
        )
    }
    registerTool(
        serverScope,
        name = "gradle_get_build_status",
        description = McpToolDescriptions.BUILD_STATUS,
        schema = buildStatusSchema(),
    ) { args ->
        val outputLimit = OutputLimitOptions.fromArgs(args)
        val progressOptions = ProgressResponseOptions.fromArgs(args)
        val projectScope = ProjectDirectoryScope(runtime.connectionManager)
        val projectDirectory = ProjectDirectoryResolver.resolveOptionalHint(
            args,
            boundary = projectScope::requireWithinBoundary,
        )
        jsonResult(
            runtime.buildExecutionManager.status(
                args.requiredString("buildId"),
                outputLimit,
                progressOptions,
                projectDirectory,
            ),
        )
    }
    registerTool(
        serverScope,
        name = "gradle_run_tasks",
        description = McpToolDescriptions.RUN_TASKS,
        schema = runTasksSchema(),
    ) { args, notifier ->
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
        val tasks = args.requiredStringList("tasks")
        val request = BuildRunRequest(
            projectDirectory = projectDirectory,
            kind = BuildKind.TASKS,
            tasks = tasks,
            arguments = args.optionalStringList("arguments").orEmpty(),
            jvmArguments = args.optionalStringList("jvmArguments").orEmpty(),
            outputLimit = OutputLimitOptions.fromArgs(args),
            progressOptions = ProgressResponseOptions.fromArgs(args),
        )
        if (args.optionalBoolean("background", default = false)) {
            jsonResult(runtime.buildExecutionManager.startBackground(request, notifier))
        } else {
            jsonResult(runtime.buildExecutionManager.runForeground(request, notifier))
        }
    }
    registerTool(
        serverScope,
        name = "gradle_run_tests",
        description = McpToolDescriptions.RUN_TESTS,
        schema = runTestsSchema(),
    ) { args, notifier ->
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
        val testOptions = parseTestRunOptions(args).validate(args.optionalString("taskPath"))
        val request = testOptions.toBuildRunRequest(
            projectDirectory = projectDirectory,
            arguments = args.optionalStringList("arguments").orEmpty(),
            jvmArguments = args.optionalStringList("jvmArguments").orEmpty(),
            outputLimit = OutputLimitOptions.fromArgs(args),
            progressOptions = ProgressResponseOptions.fromArgs(args),
        )
        if (args.optionalBoolean("background", default = false)) {
            jsonResult(runtime.buildExecutionManager.startBackground(request, notifier))
        } else {
            jsonResult(runtime.buildExecutionManager.runForeground(request, notifier))
        }
    }
}
