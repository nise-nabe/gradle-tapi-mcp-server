package com.example.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.ProjectPublications
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private val objectMapper = jacksonObjectMapper()

private const val DISCONNECT_DURING_BUILD_WARNING =
    "A build was still in progress. The server released its build slot immediately, but the Gradle " +
        "daemon may briefly run overlapping Tooling API work until the previous call unwinds."

fun main() {
    val connectionManager = GradleConnectionManager()
    val buildExecutionManager = BuildExecutionManager(connectionManager)
    connectionManager.tryAutoConnectFromEnvironment()

    val transportClosed = CountDownLatch(1)
    val transport = StdioServerTransportProvider(
        objectMapper,
        EofSignalingInputStream(System.`in`, transportClosed),
        System.out,
    )
    val server = McpServer.sync(transport)
        .serverInfo("gradle-tapi-mcp-server", "0.1.0")
        .requestTimeout(Duration.ofMinutes(30))
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(true)
                .logging()
                .build()
        )
        .tools(createTools(connectionManager, buildExecutionManager))
        .build()

    val shutdownOnce = AtomicBoolean(false)
    fun shutdownBestEffort() {
        if (!shutdownOnce.compareAndSet(false, true)) {
            return
        }
        runCatching { buildExecutionManager.shutdown() }
        runCatching { connectionManager.disconnect() }
        runCatching { server.closeGracefully() }
        transportClosed.countDown()
    }

    Runtime.getRuntime().addShutdownHook(Thread { shutdownBestEffort() })

    try {
        transportClosed.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        shutdownBestEffort()
    }
}

private fun createTools(
    connectionManager: GradleConnectionManager,
    buildExecutionManager: BuildExecutionManager,
): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_connect",
            description = "Connect to a Gradle project via the Tooling API. Call when gradle_connection_status.connected=false or to switch projects; skip when GRADLE_PROJECT_DIR auto-connect already left the server connected. When no build is running, resets the active build slot and marks any running builds as failed before connecting; rejects the call while a build slot is held. Reuses an existing compatible daemon when available.",
            schema = objectSchema(
                required = listOf("projectDirectory"),
                properties = mapOf(
                    "projectDirectory" to stringProperty("Absolute or relative path to the Gradle project root"),
                    "gradleUserHome" to stringProperty("Optional GRADLE_USER_HOME directory"),
                    "gradleVersion" to stringProperty("Optional Gradle version to download and use"),
                    "gradleInstallation" to stringProperty("Optional local Gradle installation directory"),
                ),
            ),
        ) { args ->
            if (buildExecutionManager.hasActiveBuild()) {
                error(
                    "Cannot connect while a Gradle build is running. " +
                        "Wait for the build to finish or call gradle_disconnect.",
                )
            }
            buildExecutionManager.resetBuildState("Preparing new Gradle connection")
            val config = ConnectionConfig(
                projectDirectory = args.requiredString("projectDirectory"),
                gradleUserHome = args.optionalString("gradleUserHome"),
                gradleVersion = args.optionalString("gradleVersion"),
                gradleInstallation = args.optionalString("gradleInstallation"),
            )
            jsonResult(connectionManager.connect(config))
        },
        tool(
            name = "gradle_connection_status",
            description = "Return the current Tooling API connection status and a connect-time runtime stack snapshot (gradleVersion, javaHome, javaVersion). Prefer calling this first: when GRADLE_PROJECT_DIR is set in the MCP server env (for example \${workspaceFolder} in Cursor mcp.json), the server auto-connects on startup so other Gradle tools work without gradle_connect. Auto-connect is best-effort, adds startup time (~1-2s with a warm daemon; longer on cold or large projects), and omit GRADLE_PROJECT_DIR when faster MCP startup matters. Stack fields are null with runtimeStackAvailable=false when the snapshot could not be loaded at connect. If connected=false, call gradle_connect or set GRADLE_PROJECT_DIR and restart MCP. For a fresh query including gradleUserHome and jvmArguments, use gradle_get_build_environment.",
            schema = emptyObjectSchema(),
        ) { _ ->
            jsonResult(connectionManager.status())
        },
        tool(
            name = "gradle_disconnect",
            description = "Close the active Tooling API project connection. If a build is still running, the server releases its build slot immediately; the Gradle daemon may briefly continue the prior Tooling API call until it unwinds. A warning field is included when a build was active.",
            schema = emptyObjectSchema(),
        ) { _ ->
            val hadActiveBuild = buildExecutionManager.hasActiveBuild()
            val disconnected = try {
                connectionManager.disconnect()
            } finally {
                buildExecutionManager.onDisconnect()
            }
            val payload = buildMap<String, Any?> {
                if (disconnected != null) {
                    put("projectDirectory", disconnected.projectDirectory)
                    put("state", disconnected.state)
                } else {
                    put("state", "not_connected")
                }
                if (hadActiveBuild) {
                    put("warning", DISCONNECT_DURING_BUILD_WARNING)
                }
            }
            jsonResult(payload)
        },
        tool(
            name = "gradle_get_build_environment",
            description = "Fetch BuildEnvironment (Gradle version, Gradle user home, Java home). Lightweight; prefer this over project model for stack checks.",
            schema = emptyObjectSchema(),
        ) { _ ->
            connectionManager.withConnectionResult { connection ->
                val environment = connection.getModel(BuildEnvironment::class.java)
                jsonResult(ModelSerializers.buildEnvironment(environment))
            }
        },
        tool(
            name = "gradle_get_build_cache_status",
            description = "Inspect Gradle build cache and configuration cache settings without a full build. Returns resolved cache properties (via properties -q), declared gradle.properties entries, local cache directory summaries, and optional last MCP build cache stats. Set probeConfigurationCache=true to run a lightweight configuration-cache compatibility check.",
            schema = buildCacheStatusSchema(),
        ) { args ->
            if (buildExecutionManager.hasActiveBuild()) {
                error(
                    "Cannot inspect build cache while a Gradle build is running. " +
                        "Wait for the build to finish or call gradle_get_build_status.",
                )
            }
            val options = BuildCacheStatusOptions.fromArgs(args)
            val projectDirectory = connectionManager.connectedProjectDirectory()
                ?: error("Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.")
            val lastMcpBuild = if (options.includeLastMcpBuild) {
                buildExecutionManager.lastMcpBuildInsight(projectDirectory)
            } else {
                null
            }
            connectionManager.withConnectionResult { connection ->
                jsonResult(
                    BuildCacheStatusCollector.collect(
                        connection = connection,
                        projectDirectory = projectDirectory,
                        options = options,
                        lastMcpBuild = lastMcpBuild,
                    ),
                )
            }
        },
        tool(
            name = "gradle_get_project_overview",
            description = "Fetch project hierarchy and task counts without task lists. Token-efficient default for project context ingestion.",
            schema = projectTreeSchema(),
        ) { args ->
            val treeOptions = ProjectTreeOptions.fromArgs(args)
            connectionManager.withConnectionResult { connection ->
                val project = connection.getModel(GradleProject::class.java)
                jsonResult(ModelSerializers.projectOverview(project, treeOptions))
            }
        },
        tool(
            name = "gradle_get_project_model",
            description = "Fetch the GradleProject model. Tasks are omitted by default; set includeTasks=true only when needed.",
            schema = modelQuerySchema(),
        ) { args ->
            val options = ModelQueryOptions.fromArgs(args)
            val treeOptions = ProjectTreeOptions.fromArgs(args)
            connectionManager.withConnectionResult { connection ->
                val project = connection.getModel(GradleProject::class.java)
                jsonResult(ModelSerializers.gradleProject(project, options, treeOptions))
            }
        },
        tool(
            name = "gradle_get_build_invocations",
            description = "Fetch runnable Gradle tasks. Task selectors are omitted by default; tasks return name/path/group unless includeTaskDetails=true.",
            schema = invocationsQuerySchema(),
        ) { args ->
            val options = ModelQueryOptions.fromArgs(args).copy(includeTasks = true)
            connectionManager.withConnectionResult { connection ->
                val invocations = connection.getModel(BuildInvocations::class.java)
                jsonResult(ModelSerializers.buildInvocations(invocations, options))
            }
        },
        tool(
            name = "gradle_get_project_publications",
            description = "Fetch publications declared by the build.",
            schema = emptyObjectSchema(),
        ) { _ ->
            connectionManager.withConnectionResult { connection ->
                val publications = connection.getModel(ProjectPublications::class.java)
                jsonResult(ModelSerializers.projectPublications(publications))
            }
        },
        tool(
            name = "gradle_get_build_status",
            description = "Return status and partial output for a running or completed Gradle build started with background=true. Set includeProgress=true for detailed progress (completedTasks, recentEvents). Omit buildId to use the active or most recent build.",
            schema = buildStatusSchema(),
        ) { args ->
            val outputLimit = OutputLimitOptions.fromArgs(args)
            val progressOptions = ProgressResponseOptions.fromArgs(args)
            jsonResult(
                buildExecutionManager.status(
                    args.optionalString("buildId"),
                    outputLimit,
                    progressOptions,
                ),
            )
        },
        tool(
            name = "gradle_run_tasks",
            description = "Execute Gradle task paths and return captured stdout/stderr. Use background=true to start a long build and poll gradle_get_build_status. Set includeProgress=true for detailed progress on foreground runs. Output is truncated by default (maxOutputChars=8000, tailOutput=true).",
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
                jsonResult(buildExecutionManager.startBackground(request, exchange, progressToken))
            } else {
                connectionManager.withConnectionResult { connection ->
                    jsonResult(buildExecutionManager.runForeground(request, connection, exchange, progressToken))
                }
            }
        },
        tool(
            name = "gradle_run_tests",
            description = "Execute JVM test classes and return captured stdout/stderr. Use background=true to start a long test run and poll gradle_get_build_status. Set includeProgress=true for detailed progress on foreground runs. Output is truncated by default (maxOutputChars=8000, tailOutput=true).",
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
                jsonResult(buildExecutionManager.startBackground(request, exchange, progressToken))
            } else {
                connectionManager.withConnectionResult { connection ->
                    jsonResult(buildExecutionManager.runForeground(request, connection, exchange, progressToken))
                }
            }
        },
    )

private fun tool(
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: (Map<String, Any>) -> McpSchema.CallToolResult,
): McpServerFeatures.SyncToolSpecification =
    tool(name, description, schema) { _, args, _ -> handler(args) }

private fun tool(
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: (McpSyncServerExchange, Map<String, Any>, Any?) -> McpSchema.CallToolResult,
): McpServerFeatures.SyncToolSpecification =
    McpServerFeatures.SyncToolSpecification(
        McpSchema.Tool(name, description, objectMapper.writeValueAsString(schema)),
    ) { exchange, requestOrArgs ->
        try {
            handler(
                exchange,
                extractArgs(requestOrArgs),
                extractProgressToken(requestOrArgs),
            )
        } catch (exception: Exception) {
            val code = mapExceptionToErrorCode(exception)
            structuredErrorResult(code, exception.message ?: exception.toString())
        }
    }

private fun extractArgs(requestOrArgs: Any): Map<String, Any> {
    if (requestOrArgs is McpSchema.CallToolRequest) {
        @Suppress("UNCHECKED_CAST")
        return requestOrArgs.arguments() as? Map<String, Any> ?: emptyMap()
    }
    @Suppress("UNCHECKED_CAST")
    return requestOrArgs as? Map<String, Any> ?: emptyMap()
}

private fun extractProgressToken(requestOrArgs: Any): Any? {
    if (requestOrArgs is McpSchema.CallToolRequest) {
        return McpProgressSupport.extractProgressToken(requestOrArgs)
    }
    @Suppress("UNCHECKED_CAST")
    val args = requestOrArgs as? Map<String, Any> ?: return null
    @Suppress("UNCHECKED_CAST")
    val nestedMeta = args["_meta"] as? Map<String, Any>
    return nestedMeta?.get("progressToken")
}

private fun jsonResult(value: Any?): McpSchema.CallToolResult =
    McpSchema.CallToolResult(
        listOf(McpSchema.TextContent(objectMapper.writeValueAsString(value))),
        false,
    )

private fun emptyObjectSchema(): Map<String, Any> =
    mapOf("type" to "object", "properties" to emptyMap<String, Any>())

private fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, Any>,
): Map<String, Any> =
    buildMap {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            put("required", required)
        }
    }

private fun stringProperty(description: String): Map<String, String> =
    mapOf("type" to "string", "description" to description)

private fun stringArrayProperty(description: String): Map<String, Any> =
    mapOf(
        "type" to "array",
        "description" to description,
        "items" to mapOf("type" to "string"),
    )

private fun booleanProperty(description: String): Map<String, String> =
    mapOf("type" to "boolean", "description" to description)

private fun integerProperty(description: String): Map<String, String> =
    mapOf("type" to "integer", "description" to description)

private fun projectTreeProperties(): Map<String, Any> =
    mapOf(
        "maxDepth" to integerProperty("Maximum project tree depth (root=0); deeper child projects are omitted"),
        "maxChildren" to integerProperty("Maximum child projects per node (omit for unlimited)"),
    )

private fun projectTreeSchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties())

private fun modelQueryProperties(): Map<String, Any> =
    mapOf(
        "includeTasks" to booleanProperty("Include task lists. Default false to save tokens."),
        "includeTaskDetails" to booleanProperty("Include task description and displayName. Default false."),
        "taskGroup" to stringProperty("Filter tasks by Gradle task group"),
        "taskNamePrefix" to stringProperty("Filter tasks whose name starts with this prefix"),
        "maxTasks" to integerProperty("Maximum number of tasks to return after filtering"),
    )

private fun modelQuerySchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties() + modelQueryProperties())

private fun invocationsQuerySchema(): Map<String, Any> =
    objectSchema(
        properties = modelQueryProperties() + mapOf(
            "includeTaskSelectors" to booleanProperty("Include task selectors. Default false to save tokens."),
        ),
    )

private fun progressProperties(): Map<String, Any> =
    mapOf(
        "includeProgress" to booleanProperty(
            "Include detailed progress (completedTasks, recentEvents). Default false to save tokens.",
        ),
    )

private fun buildCacheStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "includeLastMcpBuild" to booleanProperty(
                "Include task cache stats from the last MCP build run via gradle_run_tasks or gradle_run_tests (default true)",
            ),
            "includeLocalCacheDetails" to booleanProperty(
                "Include local build-cache and configuration-cache directory summaries (default true)",
            ),
            "includeDeclaredProperties" to booleanProperty(
                "Include cache-related entries from project and user gradle.properties files (default true)",
            ),
            "probeConfigurationCache" to booleanProperty(
                "Run properties -q --configuration-cache to test configuration-cache compatibility (default false)",
            ),
        ),
    )

private fun buildStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = progressProperties() + mapOf(
            "buildId" to stringProperty("Build ID returned by gradle_run_tasks or gradle_run_tests with background=true"),
            "maxOutputChars" to integerProperty(
                "Maximum stdout/stderr characters to return per stream (default ${OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS})",
            ),
            "tailOutput" to booleanProperty("When truncating, keep the tail of each stream (default true)"),
        ),
    )

private fun runOutputSchema(
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

private fun Map<String, Any>.requiredString(key: String): String {
    val value = this[key]
    if (value is String && value.isNotBlank()) {
        return value
    }
    throw McpException(
        McpErrorCode.INVALID_ARGUMENT,
        when (value) {
            null -> "Missing required argument: $key"
            is String -> "Required argument must not be blank: $key"
            else -> "Required argument must be a string: $key"
        },
    )
}

private fun Map<String, Any>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.requiredStringList(key: String): List<String> {
    when (val value = this[key]) {
        null -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Missing required argument: $key")
        !is List<*> -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a string array: $key")
        else -> {
            if (value.any { it !is String }) {
                throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must contain only strings: $key")
            }
            val strings = value.filterIsInstance<String>()
            if (strings.isEmpty()) {
                throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a non-empty string array: $key")
            }
            return strings
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.optionalStringList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it as? String }

private fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        else -> default
    }

