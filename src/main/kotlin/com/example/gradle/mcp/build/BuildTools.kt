package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.requiredStringList
import com.example.gradle.mcp.protocol.stringArrayProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures

internal fun progressProperties(): Map<String, Any> =
    mapOf(
        "includeProgress" to booleanProperty(
            "Include detailed progress (completedTasks, recentEvents). Default false to save tokens.",
        ),
    )

internal fun buildStatusSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("buildId"),
        properties = progressProperties() + mapOf(
            "buildId" to stringProperty("Build ID returned by gradle_run_tasks or gradle_run_tests with background=true"),
            "maxOutputChars" to integerProperty(
                "Maximum stdout/stderr characters to return per stream (default ${OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS})",
            ),
            "tailOutput" to booleanProperty("When truncating, keep the tail of each stream (default true)"),
        ),
    )

internal fun runOutputSchema(
    required: List<String>,
    extraProperties: Map<String, Any>,
): Map<String, Any> =
    objectSchema(
        required = required,
        properties = extraProperties + progressProperties() + mapOf(
            "arguments" to stringArrayProperty("Additional Gradle command-line arguments"),
            "jvmArguments" to stringArrayProperty("Additional JVM arguments for the build"),
            "maxOutputChars" to integerProperty(
                "Maximum stdout/stderr characters to return per stream (default ${OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS})",
            ),
            "tailOutput" to booleanProperty("When truncating, keep the tail of each stream (default true)"),
        ),
    )

context(runtime: GradleMcpRuntime)
fun buildTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_get_build_status",
            description = "Return status and partial output for a running or completed Gradle build started with background=true. buildId is required because multiple background builds may run concurrently. Completed builds include failedTaskCount, failedTasks, and buildSummary.failureSummary without includeProgress. Set includeProgress=true for the full progress object (completedTasks, recentEvents).",
            schema = buildStatusSchema(),
        ) { args ->
            val outputLimit = OutputLimitOptions.fromArgs(args)
            val progressOptions = ProgressResponseOptions.fromArgs(args)
            jsonResult(
                runtime.buildExecutionManager.status(
                    args.requiredString("buildId"),
                    outputLimit,
                    progressOptions,
                ),
            )
        },
        tool(
            name = "gradle_run_tasks",
            description = "Execute Gradle task paths and return captured stdout/stderr. Use background=true to start a long build and poll gradle_get_build_status with the returned buildId; multiple background builds may run concurrently. Set includeProgress=true for detailed progress on foreground runs. Output is truncated by default (maxOutputChars=8000, tailOutput=true).",
            schema = runOutputSchema(
                required = listOf("tasks"),
                extraProperties = mapOf(
                    "tasks" to stringArrayProperty("Gradle task paths to execute"),
                    "background" to booleanProperty("Start the build in the background and return a buildId immediately (default false)"),
                ),
            ),
        ) { exchange, args, progressToken ->
            val tasks = args.requiredStringList("tasks")
            val request = BuildRunRequest(
                kind = BuildKind.TASKS,
                tasks = tasks,
                arguments = args.optionalStringList("arguments").orEmpty(),
                jvmArguments = args.optionalStringList("jvmArguments").orEmpty(),
                outputLimit = OutputLimitOptions.fromArgs(args),
                progressOptions = ProgressResponseOptions.fromArgs(args),
            )
            if (args.optionalBoolean("background", default = false)) {
                jsonResult(runtime.buildExecutionManager.startBackground(request, exchange, progressToken))
            } else {
                runtime.connectionManager.withConnectionResult { connection ->
                    jsonResult(runtime.buildExecutionManager.runForeground(request, connection, exchange, progressToken))
                }
            }
        },
        tool(
            name = "gradle_run_tests",
            description = "Execute JVM test classes and return captured stdout/stderr. Use background=true to start a long test run and poll gradle_get_build_status with the returned buildId; multiple background test runs may run concurrently. Set includeProgress=true for detailed progress on foreground runs. Output is truncated by default (maxOutputChars=8000, tailOutput=true).",
            schema = runOutputSchema(
                required = listOf("testClasses"),
                extraProperties = mapOf(
                    "testClasses" to stringArrayProperty("Fully qualified JVM test class names"),
                    "background" to booleanProperty("Start the test run in the background and return a buildId immediately (default false)"),
                ),
            ),
        ) { exchange, args, progressToken ->
            val testClasses = args.requiredStringList("testClasses")
            val request = BuildRunRequest(
                kind = BuildKind.TESTS,
                testClasses = testClasses,
                arguments = args.optionalStringList("arguments").orEmpty(),
                jvmArguments = args.optionalStringList("jvmArguments").orEmpty(),
                outputLimit = OutputLimitOptions.fromArgs(args),
                progressOptions = ProgressResponseOptions.fromArgs(args),
            )
            if (args.optionalBoolean("background", default = false)) {
                jsonResult(runtime.buildExecutionManager.startBackground(request, exchange, progressToken))
            } else {
                runtime.connectionManager.withConnectionResult { connection ->
                    jsonResult(runtime.buildExecutionManager.runForeground(request, connection, exchange, progressToken))
                }
            }
        },
    )
