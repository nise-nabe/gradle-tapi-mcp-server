package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalBoolean
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
            "configuration" to stringProperty("Resolvable config. Omit to list names."),
            "dependency" to stringProperty("Optional substring filter (insight-like)"),
            "maxDependencies" to integerProperty("Edge cap (default 500)"),
            "maxComponents" to integerProperty("Component cap (default 500)"),
            "includeAttributes" to booleanProperty("List-mode attributes. Default false."),
            "includeOutgoingVariants" to booleanProperty("List-mode outgoing variants. Default false."),
            "maxConfigurations" to integerProperty("List cap (default 200)."),
        ),
    )

internal data class DependencyResolutionQuery(
    val projectPath: String?,
    val configuration: String?,
    val dependency: String?,
    val maxDependencies: Int,
    val maxComponents: Int,
    val includeAttributes: Boolean,
    val includeOutgoingVariants: Boolean,
    val maxConfigurations: Int,
    val prepareTasks: List<String>,
)

internal fun parseDependencyResolutionQuery(args: Map<String, Any>): DependencyResolutionQuery =
    DependencyResolutionQuery(
        projectPath = args.optionalString("projectPath")?.trim()?.takeIf { it.isNotEmpty() },
        configuration = args.optionalString("configuration")?.trim()?.takeIf { it.isNotEmpty() },
        dependency = args.optionalString("dependency")?.trim()?.takeIf { it.isNotEmpty() },
        maxDependencies = args.nonNegativeIntOrDefault("maxDependencies"),
        maxComponents = args.nonNegativeIntOrDefault("maxComponents"),
        includeAttributes = args.optionalBoolean("includeAttributes", default = false),
        includeOutgoingVariants = args.optionalBoolean("includeOutgoingVariants", default = false),
        maxConfigurations = args.nonNegativeIntOrDefault("maxConfigurations"),
        prepareTasks = args.optionalStringList("prepareTasks").orEmpty()
            .filter { it.isNotBlank() }
            .distinct(),
    )

context(runtime: GradleMcpRuntime)
fun Server.registerDependencyResolutionTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_dependency_resolution",
        description = McpToolDescriptions.DEPENDENCY_RESOLUTION,
        schema = dependencyResolutionSchema(),
    ) { args ->
        val query = parseDependencyResolutionQuery(args)
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
                fetchDependencyResolution(connection, query)
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
    query: DependencyResolutionQuery,
): McpDependencyResolution {
    val initScript = DependencyResolutionInitScriptProvider.initScriptPath()
    val action = FetchDependencyResolutionAction(
        query.projectPath,
        query.configuration,
        query.dependency,
        query.maxDependencies,
        query.maxComponents,
        query.includeAttributes,
        query.includeOutgoingVariants,
        query.maxConfigurations,
    )
    val executer = connection.action(action)
        .withArguments("--init-script", initScript)
    if (query.prepareTasks.isNotEmpty()) {
        try {
            executer.forTasks(*query.prepareTasks.toTypedArray())
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

internal fun mapResolutionFailure(exception: Exception): McpException {
    val message = deepestMessage(exception)
    val invalid = message.contains("configuration is required", ignoreCase = true) ||
        message.contains("Unknown configuration", ignoreCase = true) ||
        message.contains("Unknown projectPath", ignoreCase = true) ||
        message.contains("is not resolvable", ignoreCase = true) ||
        message.contains("requires parameters", ignoreCase = true)
    if (invalid) {
        val details = if (
            message.contains("Unknown configuration", ignoreCase = true) ||
            message.contains("is not resolvable", ignoreCase = true)
        ) {
            suggestedConfigurationErrorDetails(message)
        } else {
            emptyMap()
        }
        return McpException(
            McpErrorCode.INVALID_ARGUMENT,
            message,
            exception,
            errorDetails = details,
        )
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

internal fun suggestedConfigurationErrorDetails(message: String): Map<String, Any?> {
    val parsed = parseResolvableSuffix(message)
    return buildMap {
        if (parsed != null) {
            put("suggestedConfigurations", parsed.names)
            if (parsed.truncated) {
                put("suggestedConfigurationsTruncated", true)
            }
        }
        put(
            "hint",
            "Omit configuration on gradle_get_dependency_resolution to list resolvable and consumable names.",
        )
    }
}

internal data class ResolvableSuffix(
    val names: List<String>,
    val truncated: Boolean,
)

internal fun parseResolvableSuffix(message: String): ResolvableSuffix? {
    val match = RESOLVABLE_SUFFIX.find(message) ?: return null
    val rawNames = match.groupValues[1].trim()
    val names = if (rawNames == "(none)") {
        emptyList()
    } else {
        rawNames.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val truncated = match.groupValues[2].isNotEmpty()
    return ResolvableSuffix(names, truncated)
}

private val RESOLVABLE_SUFFIX =
    Regex("""Resolvable: (.+?)(?: \(\+(\d+) more\))?$""")

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
