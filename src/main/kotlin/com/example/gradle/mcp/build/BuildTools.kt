package com.example.gradle.mcp.build

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryScope
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.optionalPositiveInt
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.testMethodsProperty
import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.requiredStringList
import com.example.gradle.mcp.protocol.stringArrayProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures
import java.io.File

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

internal fun resolveScopedProjectDirectory(
    args: Map<String, Any>,
    scope: ProjectDirectoryScope,
): File? =
    args.optionalString("projectDirectory")?.let { path ->
        val directory = File(path)
        if (!directory.isDirectory) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory is not a directory: $path",
            )
        }
        scope.requireWithinBoundary(directory)
    }

internal fun listBuildsSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to stringProperty(
                "Gradle project root for scanning .gradle/mcp-builds/. Defaults to the connected project, " +
                    "then GRADLE_PROJECT_DIR when set. Must stay within that workspace boundary when provided explicitly. " +
                    "In-memory builds from this server are always included.",
            ),
            "limit" to integerProperty(
                "Maximum builds to return, most recent first (default ${BuildExecutionManager.DEFAULT_LIST_BUILDS}, max ${BuildExecutionManager.MAX_LIST_BUILDS})",
            ),
        ),
    )

internal fun buildStatusSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("buildId"),
        properties = progressProperties() + outputProperties() + mapOf(
            "buildId" to stringProperty("Build ID returned by gradle_run_tasks or gradle_run_tests with background=true"),
            "projectDirectory" to stringProperty(
                "Gradle project root directory for disk-only status lookup when the in-memory record was evicted " +
                    "and the MCP connection points at a different project. Defaults to the connected project. " +
                    "Must stay within the GRADLE_PROJECT_DIR or connected-project workspace boundary.",
            ),
        ),
    )

internal fun runOutputSchema(
    required: List<String>,
    extraProperties: Map<String, Any>,
): Map<String, Any> =
    objectSchema(
        required = required,
        properties = extraProperties + progressProperties() + outputProperties() + mapOf(
            "arguments" to stringArrayProperty(
                "Additional Gradle command-line arguments (init scripts via --init-script or -I, and @argument files, are not allowed)",
            ),
            "jvmArguments" to stringArrayProperty("Additional JVM arguments for the build"),
        ),
    )

context(runtime: GradleMcpRuntime)
fun buildTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_list_builds",
            description = "List recent MCP Gradle builds from in-memory records and .gradle/mcp-builds/ on disk. Does not require an active Tooling API connection. Use when a buildId was lost or to discover builds to poll with gradle_get_build_status. Each build summary always includes buildId, status, tasks, testClasses, and recordSource (memory|disk). Optional fields omitted when absent: kind, projectDirectory, startedAt, finishedAt, outcome (e.g. running builds omit outcome; Gradle-only disk records may omit kind). Sorted by finishedAt or startedAt, most recent first.",
            schema = listBuildsSchema(),
        ) { args ->
            val projectDirectory = resolveScopedProjectDirectory(args, ProjectDirectoryScope(runtime.connectionManager))
            val limit = args.optionalPositiveInt("limit") ?: BuildExecutionManager.DEFAULT_LIST_BUILDS
            jsonResult(runtime.buildExecutionManager.listBuilds(projectDirectory, limit))
        },
        tool(
            name = "gradle_cancel_build",
            description = "Cancel a background Gradle build started with background=true. Uses the Tooling API CancellationToken to stop the Gradle daemon build. Returns immediately with cancellation requested; poll gradle_get_build_status until status is no longer running, then inspect the terminal status (cancelled, succeeded, or failed). No-op when the build already finished.",
            schema = objectSchema(
                required = listOf("buildId"),
                properties = mapOf(
                    "buildId" to stringProperty("Build ID returned by gradle_run_tasks or gradle_run_tests with background=true"),
                ),
            ),
        ) { args ->
            jsonResult(runtime.buildExecutionManager.cancelBuild(args.requiredString("buildId")))
        },
        tool(
            name = "gradle_get_build_status",
            description = "Return status for a running or completed Gradle build started with background=true. buildId is required because multiple background builds may run concurrently. Status values: running, succeeded, failed, cancelled, not_found. Default response is outcome and buildSummary only (no stdout/stderr task log). Set includeOutput=true for captured stdout/stderr; while the build is running, live output is available only when the MCP server still holds the in-memory record—disk-only polls (after restart or memory eviction) return stdout/stderr only after MCP finalizes logs at build end. Disk-backed polls include statusSource (memory|disk), and when statusSource is disk also liveProgress=false, progressAvailable, and recordDirectory. Gradle on-disk records override in-memory status while Gradle is still active; stale Gradle running (MCP terminal, no post-finalize events) falls back to MCP. Optional projectDirectory locates disk artifacts when the in-memory record is gone and the connected project differs. Completed builds include failedTaskCount, failedTasks, and buildSummary.failureSummary without includeProgress when available (in-memory, MCP-terminal disk, or Gradle-terminal failed with events.ndjson). Set includeProgress=true for the full progress object (completedTasks, recentEvents); disk progress uses events.ndjson (task and test events).",
            schema = buildStatusSchema(),
        ) { args ->
            val outputLimit = OutputLimitOptions.fromArgs(args)
            val progressOptions = ProgressResponseOptions.fromArgs(args)
            val projectDirectory = resolveScopedProjectDirectory(args, ProjectDirectoryScope(runtime.connectionManager))
            jsonResult(
                runtime.buildExecutionManager.status(
                    args.requiredString("buildId"),
                    outputLimit,
                    progressOptions,
                    projectDirectory,
                ),
            )
        },
        tool(
            name = "gradle_run_tasks",
            description = "Execute Gradle task paths and return build outcome and summary. stdout/stderr omitted by default (no UP-TO-DATE / task log noise); set includeOutput=true to include captured output. Use background=true to start a long build and poll gradle_get_build_status with the returned buildId; call gradle_cancel_build to stop an unneeded background build. Multiple background builds may run concurrently. Set includeProgress=true for detailed progress on foreground runs.",
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
            description = "Execute JVM tests with class, method, pattern, or task-scoped selection and return build outcome and summary. " +
                "Provide exactly one of testClasses, testMethods, or includePattern/includePatterns (patterns require tasks). " +
                "Optional taskPath scopes classes/methods via withTaskAndTest*; optional tasks limits test tasks via TestLauncher.forTasks(). " +
                "stdout/stderr omitted by default (no UP-TO-DATE / task log noise); set includeOutput=true to include captured output. " +
                "Use background=true to start a long test run and poll gradle_get_build_status with the returned buildId; " +
                "call gradle_cancel_build to stop an unneeded background test run. Multiple background test runs may run concurrently. " +
                "Set includeProgress=true for detailed progress on foreground runs.",
            schema = runOutputSchema(
                required = emptyList(),
                extraProperties = mapOf(
                    "testClasses" to stringArrayProperty("Fully qualified JVM test class names (withJvmTestClasses / withTaskAndTestClasses)"),
                    "testMethods" to testMethodsProperty(
                        "Map of class name to method names, e.g. {\"com.example.FooTest\": [\"method1\"]}, " +
                            "or array form [{\"class\": \"com.example.FooTest\", \"methods\": [\"method1\"]}]",
                    ),
                    "taskPath" to stringProperty(
                        "Gradle test task path for withTaskAndTestClasses / withTaskAndTestMethods (Gradle 6.1+). Requires testClasses or testMethods.",
                    ),
                    "includePattern" to stringProperty("Single test include pattern for withTestsFor TestSpec API (Gradle 7.6+). Requires tasks."),
                    "includePatterns" to stringArrayProperty(
                        "Test include patterns for withTestsFor TestSpec API (Gradle 7.6+). Requires tasks.",
                    ),
                    "tasks" to stringArrayProperty(
                        "Optional Gradle test task paths for TestLauncher.forTasks() (Gradle 7.6+). Required when using includePattern/includePatterns.",
                    ),
                    "background" to booleanProperty("Start the test run in the background and return a buildId immediately (default false)"),
                ),
            ),
        ) { exchange, args, progressToken ->
            val testOptions = parseTestRunOptions(args).validate()
            val request = testOptions.toBuildRunRequest(
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
