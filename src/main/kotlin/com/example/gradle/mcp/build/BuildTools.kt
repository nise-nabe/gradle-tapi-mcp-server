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

internal fun outputProperties(): Map<String, Any> =
    mapOf(
        "includeOutput" to booleanProperty(
            "Include stdout/stderr in the response. Default false returns outcome and buildSummary only (no task log lines such as UP-TO-DATE).",
        ),
        "maxOutputChars" to integerProperty(
            "When includeOutput is true, maximum stdout/stderr characters per stream (default ${OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS})",
        ),
        "tailOutput" to booleanProperty("When truncating output, keep the tail of each stream (default true)"),
    )

internal fun buildStatusSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("buildId"),
        properties = progressProperties() + outputProperties() + mapOf(
            "buildId" to stringProperty("Build ID returned by gradle_run_tasks or gradle_run_tests with background=true"),
        ),
    )

internal fun runOutputSchema(
    required: List<String>,
    extraProperties: Map<String, Any>,
): Map<String, Any> =
    objectSchema(
        required = required,
        properties = extraProperties + progressProperties() + outputProperties() + mapOf(
            "arguments" to stringArrayProperty("Additional Gradle command-line arguments"),
            "jvmArguments" to stringArrayProperty("Additional JVM arguments for the build"),
        ),
    )

context(runtime: GradleMcpRuntime)
fun buildTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_get_build_status",
            description = "Return status for a running or completed Gradle build started with background=true. buildId is required because multiple background builds may run concurrently. Default response is outcome and buildSummary only (no stdout/stderr task log). Set includeOutput=true for captured stdout/stderr. Completed builds include failedTaskCount, failedTasks, and buildSummary.failureSummary without includeProgress. Set includeProgress=true for the full progress object (completedTasks, recentEvents).",
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
            description = "Execute Gradle task paths and return build outcome and summary. stdout/stderr omitted by default (no UP-TO-DATE / task log noise); set includeOutput=true to include captured output. Use background=true to start a long build and poll gradle_get_build_status with the returned buildId; multiple background builds may run concurrently. Set includeProgress=true for detailed progress on foreground runs.",
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
            description = "Execute JVM test classes and return build outcome and summary. stdout/stderr omitted by default (no UP-TO-DATE / task log noise); set includeOutput=true to include captured output. Use background=true to start a long test run and poll gradle_get_build_status with the returned buildId; multiple background test runs may run concurrently. Set includeProgress=true for detailed progress on foreground runs.",
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
