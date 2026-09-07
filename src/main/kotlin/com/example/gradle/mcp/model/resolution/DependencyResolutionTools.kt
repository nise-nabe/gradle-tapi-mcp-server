package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalNonNegativeInt
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.prepareTasksProperty
import com.example.gradle.mcp.protocol.registerTool
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.resolution.FetchDependencyResolutionAction
import com.example.gradle.mcp.resolution.McpDependencyResolution
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.exceptions.UnsupportedOperationConfigurationException

internal fun dependencyResolutionSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
            "prepareTasks" to prepareTasksProperty(),
            "projectPath" to stringProperty("Subproject path; default root"),
            "configuration" to stringProperty("Resolvable config e.g. runtimeClasspath"),
            "dependency" to stringProperty("Optional substring filter (insight-like)"),
            "maxDependencies" to integerProperty("Edge cap (default 500)"),
            "maxComponents" to integerProperty("Component cap (default 500)"),
        ),
        required = listOf("configuration"),
    )

context(runtime: GradleMcpRuntime)
fun Server.registerDependencyResolutionTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_dependency_resolution",
        description = McpToolDescriptions.DEPENDENCY_RESOLUTION,
        schema = dependencyResolutionSchema(),
    ) { args ->
        val configuration = args.optionalString("configuration")?.trim().orEmpty()
        if (configuration.isEmpty()) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "configuration is required (e.g. runtimeClasspath, compileClasspath)",
            )
        }
        val projectPath = args.optionalString("projectPath")?.trim()?.takeIf { it.isNotEmpty() }
        val dependency = args.optionalString("dependency")?.trim()?.takeIf { it.isNotEmpty() }
        val maxDependencies = args.nonNegativeIntOrDefault("maxDependencies")
        val maxComponents = args.nonNegativeIntOrDefault("maxComponents")
        val prepareTasks = args.optionalStringList("prepareTasks").orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)

        val model = ProjectLifecycleGuard.withNoActiveBuild(
            projectDirectory = projectDirectory,
            buildExecutionManager = runtime.buildExecutionManager,
            message = { directory ->
                "Cannot query dependency resolution while a build is active for ${directory.path}. " +
                    "Wait for the build to finish, call gradle_cancel_build, or poll gradle_get_build_status."
            },
        ) {
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                fetchDependencyResolution(
                    connection = connection,
                    projectPath = projectPath,
                    configuration = configuration,
                    dependency = dependency,
                    maxDependencies = maxDependencies,
                    maxComponents = maxComponents,
                    prepareTasks = prepareTasks,
                )
            }
        }
        jsonResult(DependencyResolutionSerializers.toMap(model))
    }
}

/**
 * Uses [optionalNonNegativeInt] (exact Int conversion). Missing key → 0 (builder default).
 * Present but invalid / negative / overflowing → [McpErrorCode.INVALID_ARGUMENT].
 */
internal fun Map<String, Any>.nonNegativeIntOrDefault(key: String, default: Int = 0): Int {
    if (!containsKey(key)) {
        return default
    }
    return optionalNonNegativeInt(key)
        ?: throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "$key must be a non-negative integer within Int range",
        )
}

internal fun fetchDependencyResolution(
    connection: ProjectConnection,
    projectPath: String?,
    configuration: String,
    dependency: String?,
    maxDependencies: Int,
    maxComponents: Int,
    prepareTasks: List<String>,
): McpDependencyResolution {
    val initScript = DependencyResolutionInitScriptProvider.initScriptPath()
    val action = FetchDependencyResolutionAction(
        projectPath,
        configuration,
        dependency,
        maxDependencies,
        maxComponents,
    )
    val executer = connection.action(action)
        .withArguments("--init-script", initScript)
    if (prepareTasks.isNotEmpty()) {
        try {
            executer.forTasks(*prepareTasks.toTypedArray())
        } catch (exception: UnsupportedOperationConfigurationException) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "prepareTasks is not supported for dependency resolution on this Gradle version",
                exception,
            )
        }
    }
    return try {
        executer.run()
    } catch (exception: Exception) {
        if (exception is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        throw mapResolutionFailure(exception)
    }
}

private fun mapResolutionFailure(exception: Exception): McpException {
    val message = deepestMessage(exception)
    val invalid = message.contains("configuration is required", ignoreCase = true) ||
        message.contains("Unknown configuration", ignoreCase = true) ||
        message.contains("Unknown projectPath", ignoreCase = true) ||
        message.contains("is not resolvable", ignoreCase = true) ||
        message.contains("requires parameters", ignoreCase = true)
    if (invalid) {
        return McpException(McpErrorCode.INVALID_ARGUMENT, message, exception)
    }
    if (exception is BuildException || exception is GradleConnectionException) {
        return McpException(
            McpErrorCode.BUILD_FAILED,
            "Dependency resolution model failed: $message",
            exception,
        )
    }
    return McpException(
        McpErrorCode.INTERNAL_ERROR,
        "Dependency resolution model failed: $message",
        exception,
    )
}

private fun deepestMessage(exception: Throwable): String {
    var current: Throwable? = exception
    var best = exception.message?.takeIf { it.isNotBlank() }
    while (current != null) {
        val msg = current.message
        if (!msg.isNullOrBlank()) {
            best = msg
        }
        current = current.cause
    }
    return best ?: exception.javaClass.simpleName
}
