package org.gradle.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.ProjectPublications
import java.time.Duration

private val objectMapper = ObjectMapper()

fun main() {
    val connectionManager = GradleConnectionManager()
    connectionManager.tryAutoConnectFromEnvironment()

    val transport = StdioServerTransportProvider(objectMapper)
    val server = McpServer.sync(transport)
        .serverInfo("gradle-tapi-mcp-server", "0.1.0")
        .requestTimeout(Duration.ofMinutes(30))
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build()
        )
        .tools(createTools(connectionManager))
        .build()

    Runtime.getRuntime().addShutdownHook(Thread {
        connectionManager.disconnect()
        server.close()
    })

    joinNonDaemonWorkerThreads()
}

private fun createTools(connectionManager: GradleConnectionManager): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_connect",
            description = "Connect to a Gradle project via the Tooling API. Reuses an existing compatible daemon when available.",
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
            description = "Return the current Tooling API connection status.",
            schema = emptyObjectSchema(),
        ) { _ ->
            jsonResult(connectionManager.status())
        },
        tool(
            name = "gradle_disconnect",
            description = "Close the active Tooling API project connection.",
            schema = emptyObjectSchema(),
        ) { _ ->
            jsonResult(connectionManager.disconnect() ?: mapOf("state" to "not_connected"))
        },
        tool(
            name = "gradle_get_build_environment",
            description = "Fetch BuildEnvironment (Gradle version, Gradle user home, Java home/version).",
            schema = emptyObjectSchema(),
        ) { _ ->
            connectionManager.withConnectionResult { connection ->
                val environment = connection.getModel(BuildEnvironment::class.java)
                jsonResult(ModelSerializers.buildEnvironment(environment))
            }
        },
        tool(
            name = "gradle_get_project_model",
            description = "Fetch the GradleProject model including subprojects and tasks.",
            schema = emptyObjectSchema(),
        ) { _ ->
            connectionManager.withConnectionResult { connection ->
                val project = connection.getModel(GradleProject::class.java)
                jsonResult(ModelSerializers.gradleProject(project))
            }
        },
        tool(
            name = "gradle_get_build_invocations",
            description = "Fetch runnable tasks and task selectors exposed by the build.",
            schema = emptyObjectSchema(),
        ) { _ ->
            connectionManager.withConnectionResult { connection ->
                val invocations = connection.getModel(BuildInvocations::class.java)
                jsonResult(ModelSerializers.buildInvocations(invocations))
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
            name = "gradle_run_tasks",
            description = "Execute Gradle task paths and return captured stdout/stderr.",
            schema = objectSchema(
                required = listOf("tasks"),
                properties = mapOf(
                    "tasks" to stringArrayProperty("Gradle task paths to execute"),
                    "arguments" to stringArrayProperty("Additional Gradle command-line arguments"),
                    "jvmArguments" to stringArrayProperty("Additional JVM arguments for the build"),
                ),
            ),
        ) { args ->
            val tasks = args.requiredStringList("tasks")
            val arguments = args.optionalStringList("arguments").orEmpty()
            val jvmArguments = args.optionalStringList("jvmArguments").orEmpty()
            connectionManager.withConnectionResult { connection ->
                val streams = CapturingStreams()
                val launcher = connection.newBuild()
                    .forTasks(*tasks.toTypedArray())
                    .addArguments(arguments)
                    .addJvmArguments(jvmArguments)
                streams.applyTo(launcher)
                launcher.run()
                jsonResult(
                    mapOf(
                        "tasks" to tasks,
                        "stdout" to streams.stdoutText(),
                        "stderr" to streams.stderrText(),
                    )
                )
            }
        },
        tool(
            name = "gradle_run_tests",
            description = "Execute JVM test classes and return captured stdout/stderr.",
            schema = objectSchema(
                required = listOf("testClasses"),
                properties = mapOf(
                    "testClasses" to stringArrayProperty("Fully qualified JVM test class names"),
                    "arguments" to stringArrayProperty("Additional Gradle command-line arguments"),
                    "jvmArguments" to stringArrayProperty("Additional JVM arguments for the build"),
                ),
            ),
        ) { args ->
            val testClasses = args.requiredStringList("testClasses")
            val arguments = args.optionalStringList("arguments").orEmpty()
            val jvmArguments = args.optionalStringList("jvmArguments").orEmpty()
            connectionManager.withConnectionResult { connection ->
                val streams = CapturingStreams()
                val launcher = connection.newTestLauncher()
                    .withJvmTestClasses(*testClasses.toTypedArray())
                    .addArguments(arguments)
                    .addJvmArguments(jvmArguments)
                streams.applyTo(launcher)
                launcher.run()
                jsonResult(
                    mapOf(
                        "testClasses" to testClasses,
                        "stdout" to streams.stdoutText(),
                        "stderr" to streams.stderrText(),
                    )
                )
            }
        },
    )

private fun tool(
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: (Map<String, Any>) -> McpSchema.CallToolResult,
): McpServerFeatures.SyncToolSpecification =
    McpServerFeatures.SyncToolSpecification(
        McpSchema.Tool(name, description, objectMapper.writeValueAsString(schema)),
    ) { _, arguments ->
        try {
            @Suppress("UNCHECKED_CAST")
            handler(arguments as Map<String, Any>)
        } catch (exception: Exception) {
            errorResult(exception.message ?: exception.toString())
        }
    }

private fun jsonResult(value: Any?): McpSchema.CallToolResult =
    McpSchema.CallToolResult(
        listOf(McpSchema.TextContent(objectMapper.writeValueAsString(value))),
        false,
    )

private fun errorResult(message: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult(
        listOf(McpSchema.TextContent(message)),
        true,
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

private fun Map<String, Any>.requiredString(key: String): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() }
        ?: error("Missing required argument: $key")

private fun Map<String, Any>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.requiredStringList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() }
        ?: error("Missing required argument: $key")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.optionalStringList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it as? String }

private fun joinNonDaemonWorkerThreads() {
    Thread.getAllStackTraces().keys
        .filter { !it.isDaemon && it != Thread.currentThread() }
        .forEach { thread ->
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
}
