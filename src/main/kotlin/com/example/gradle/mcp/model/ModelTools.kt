package com.example.gradle.mcp.model

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.prepareTasksProperty
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnknownModelException
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.build.Help
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.gradle.ProjectPublications
import java.io.File

private fun modelDirectoryProperties(): Map<String, Any> =
    mapOf(
        "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
        "prepareTasks" to prepareTasksProperty(),
    )

internal fun projectTreeProperties(): Map<String, Any> =
    mapOf(
        "maxDepth" to integerProperty("Max project tree depth (root=0)"),
        "maxChildren" to integerProperty("Max child projects per node"),
    ) + modelDirectoryProperties()

internal fun projectTreeSchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties())

internal fun modelQueryProperties(): Map<String, Any> =
    mapOf(
        "includeTasks" to booleanProperty("Include task lists. Default false."),
        "includeTaskDetails" to booleanProperty("Include task description and displayName. Default false."),
        "taskGroup" to stringProperty("Filter by Gradle task group"),
        "taskNamePrefix" to stringProperty("Filter by task name prefix"),
        "maxTasks" to integerProperty("Max tasks after filtering"),
    )

internal fun modelQuerySchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties() + modelQueryProperties())

internal fun buildInvocationsQuerySchema(): Map<String, Any> =
    objectSchema(
        properties = projectTreeProperties() + modelQueryProperties() + mapOf(
            "includeTaskSelectors" to booleanProperty("Include task selectors. Default false."),
        ),
    )

internal fun publicationsSchema(): Map<String, Any> =
    objectSchema(properties = modelDirectoryProperties())

internal fun helpSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "maxChars" to integerProperty(
                "Max help characters (default ${HelpLimitOptions.DEFAULT_MAX_CHARS})",
            ),
            "tailOutput" to booleanProperty("Keep tail when truncated. Default true."),
        ) + modelDirectoryProperties(),
    )

private fun prepareTasksFromArgs(args: Map<String, Any>): List<String> =
    args.optionalStringList("prepareTasks").orEmpty().filter { it.isNotBlank() }.distinct()

internal fun requireNoActiveBuildForPrepareTasks(
    prepareTasks: List<String>,
    projectDirectory: File,
    buildExecutionManager: BuildExecutionManager,
) {
    ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = buildExecutionManager,
        message = { directory -> modelQueryBlockedMessage(prepareTasks, directory) },
    ) { }
}

private fun modelQueryBlockedMessage(prepareTasks: List<String>, projectDirectory: File): String =
    if (prepareTasks.isEmpty()) {
        "Cannot query Gradle models while a build is running for ${projectDirectory.path}. " +
            "Wait for the build to finish, call gradle_cancel_build, or poll gradle_get_build_status."
    } else {
        "Cannot run prepareTasks while a Gradle build is running for ${projectDirectory.path}. " +
            "Wait for the build to finish or call gradle_get_build_status."
    }

private fun fetchHelpModel(connection: ProjectConnection, prepareTasks: List<String>): Help =
    try {
        connection.fetchModel(Help::class.java, prepareTasks)
    } catch (exception: Exception) {
        if (exception is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        when (exception) {
            is UnknownModelException, is UnsupportedVersionException -> throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Help model is not available. Gradle 9.4 or later is required.",
                exception,
            )
            else -> throw exception
        }
    }

context(runtime: GradleMcpRuntime)
private inline fun <T> fetchModelJson(
    args: Map<String, Any>,
    crossinline fetch: (ProjectConnection, List<String>) -> T,
    crossinline serialize: (T) -> Any,
): CallToolResult {
    val prepareTasks = prepareTasksFromArgs(args)
    val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
    return ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = runtime.buildExecutionManager,
        message = { directory -> modelQueryBlockedMessage(prepareTasks, directory) },
    ) {
        runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
            jsonResult(serialize(fetch(connection, prepareTasks)))
        }
    }
}

context(runtime: GradleMcpRuntime)
fun Server.registerModelTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_project_overview",
        description = McpToolDescriptions.PROJECT_OVERVIEW,
        schema = projectTreeSchema(),
    ) { args ->
        val treeOptions = ProjectTreeOptions.fromArgs(args)
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                connection.fetchModel(GradleProject::class.java, prepareTasks)
            },
            serialize = { project -> ModelSerializers.projectOverview(project, treeOptions) },
        )
    }
    registerTool(
        scope,
        name = "gradle_get_gradle_build",
        description = McpToolDescriptions.GRADLE_BUILD,
        schema = projectTreeSchema(),
    ) { args ->
        val treeOptions = ProjectTreeOptions.fromArgs(args)
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                connection.fetchModel(GradleBuild::class.java, prepareTasks)
            },
            serialize = { build -> ModelSerializers.gradleBuild(build, treeOptions) },
        )
    }
    registerTool(
        scope,
        name = "gradle_get_project_model",
        description = McpToolDescriptions.PROJECT_MODEL,
        schema = modelQuerySchema(),
    ) { args ->
        val options = ModelQueryOptions.fromArgs(args)
        val treeOptions = ProjectTreeOptions.fromArgs(args)
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                connection.fetchModel(GradleProject::class.java, prepareTasks)
            },
            serialize = { project -> ModelSerializers.gradleProject(project, options, treeOptions) },
        )
    }
    registerTool(
        scope,
        name = "gradle_get_build_invocations",
        description = McpToolDescriptions.BUILD_INVOCATIONS,
        schema = buildInvocationsQuerySchema(),
    ) { args ->
        val options = ModelQueryOptions.fromArgs(args).copy(includeTasks = true)
        val treeOptions = ProjectTreeOptions.fromArgs(args)
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                val invocations = connection.fetchModel(BuildInvocations::class.java, prepareTasks)
                val project = connection.fetchModel(GradleProject::class.java, prepareTasks)
                invocations to project
            },
            serialize = { (invocations, project) ->
                ModelSerializers.buildInvocations(invocations, project, options, treeOptions)
            },
        )
    }
    registerTool(
        scope,
        name = "gradle_get_project_publications",
        description = McpToolDescriptions.PROJECT_PUBLICATIONS,
        schema = publicationsSchema(),
    ) { args ->
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                connection.fetchModel(ProjectPublications::class.java, prepareTasks)
            },
            serialize = ModelSerializers::projectPublications,
        )
    }
    registerTool(
        scope,
        name = "gradle_get_help",
        description = McpToolDescriptions.HELP,
        schema = helpSchema(),
    ) { args ->
        val limitOptions = HelpLimitOptions.fromArgs(args)
        fetchModelJson(
            args,
            fetch = ::fetchHelpModel,
            serialize = { help -> ModelSerializers.help(help, limitOptions) },
        )
    }
}
