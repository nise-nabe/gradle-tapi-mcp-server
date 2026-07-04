package com.example.gradle.mcp.model

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.stringArrayProperty
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
        "projectDirectory" to resolveRequiredProjectDirectoryProperty(
            "Gradle project root to query.",
        ),
        "prepareTasks" to prepareTasksProperty(),
    )

internal fun projectTreeProperties(): Map<String, Any> =
    mapOf(
        "maxDepth" to integerProperty("Maximum project tree depth (root=0); deeper child projects are omitted"),
        "maxChildren" to integerProperty("Maximum child projects per node (omit for unlimited)"),
    ) + modelDirectoryProperties()

internal fun prepareTasksProperty(): Map<String, Any> =
    stringArrayProperty(
        "Optional prepareTasks runs tasks before model fetch (e.g. [\":app:compileJava\"]). " +
            "Empty or omitted preserves the current lightweight behavior.",
    )

private const val PREPARE_TASKS_TOOL_NOTE =
    "Optional prepareTasks runs tasks before model fetch (e.g. [\":app:compileJava\"])."

internal fun projectTreeSchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties())

internal fun modelQueryProperties(): Map<String, Any> =
    mapOf(
        "includeTasks" to booleanProperty("Include task lists. Default false to save tokens."),
        "includeTaskDetails" to booleanProperty("Include task description and displayName. Default false."),
        "taskGroup" to stringProperty("Filter tasks by Gradle task group"),
        "taskNamePrefix" to stringProperty("Filter tasks whose name starts with this prefix"),
        "maxTasks" to integerProperty("Maximum number of tasks to return after filtering"),
    )

internal fun modelQuerySchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties() + modelQueryProperties())

internal fun invocationsQuerySchema(): Map<String, Any> =
    objectSchema(
        properties = modelDirectoryProperties() + modelQueryProperties() + mapOf(
            "includeTaskSelectors" to booleanProperty("Include task selectors. Default false to save tokens."),
        ),
    )

internal fun publicationsSchema(): Map<String, Any> =
    objectSchema(properties = modelDirectoryProperties())

internal fun helpSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "maxChars" to integerProperty(
                "Maximum rendered help characters to return (default ${HelpLimitOptions.DEFAULT_MAX_CHARS})",
            ),
            "tailOutput" to booleanProperty(
                "When truncated, keep the tail of the help text (default true)",
            ),
        ) + modelDirectoryProperties(),
    )

private fun prepareTasksFromArgs(args: Map<String, Any>): List<String> =
    args.optionalStringList("prepareTasks").orEmpty().filter { it.isNotBlank() }.distinct()

internal fun requireNoActiveBuildForPrepareTasks(
    prepareTasks: List<String>,
    projectDirectory: File,
    buildExecutionManager: BuildExecutionManager,
) {
    if (prepareTasks.isEmpty()) {
        return
    }
    if (buildExecutionManager.hasActiveBuild(projectDirectory)) {
        throw McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Cannot run prepareTasks while a Gradle build is running for ${projectDirectory.path}. " +
                "Wait for the build to finish or call gradle_get_build_status.",
        )
    }
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
    requireNoActiveBuildForPrepareTasks(prepareTasks, projectDirectory, runtime.buildExecutionManager)
    return runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
        jsonResult(serialize(fetch(connection, prepareTasks)))
    }
}

context(runtime: GradleMcpRuntime)
fun Server.registerModelTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_project_overview",
        description = "Fetch project hierarchy and task counts without task lists. Token-efficient default for project context ingestion. $PREPARE_TASKS_TOOL_NOTE",
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
        description = "Fetch GradleBuild structure: root project tree, all projects, included builds, and editable builds. Lightweight and read-only by default; no tasks unless prepareTasks is set. $PREPARE_TASKS_TOOL_NOTE Prefer for composite or includeBuild repositories.",
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
        description = "Fetch the GradleProject model. Tasks are omitted by default; set includeTasks=true only when needed. $PREPARE_TASKS_TOOL_NOTE",
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
        description = "Fetch runnable Gradle tasks. Task selectors are omitted by default; tasks return name/path/group unless includeTaskDetails=true. $PREPARE_TASKS_TOOL_NOTE",
        schema = invocationsQuerySchema(),
    ) { args ->
        val options = ModelQueryOptions.fromArgs(args).copy(includeTasks = true)
        fetchModelJson(
            args,
            fetch = { connection, prepareTasks ->
                connection.fetchModel(BuildInvocations::class.java, prepareTasks)
            },
            serialize = { invocations -> ModelSerializers.buildInvocations(invocations, options) },
        )
    }
    registerTool(
        scope,
        name = "gradle_get_project_publications",
        description = "Fetch publications declared by the build. $PREPARE_TASKS_TOOL_NOTE",
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
        description = "Fetch Gradle CLI help text (equivalent to `gradle --help`). Requires Gradle 9.4+; returns a structured error if the Help model is unavailable. $PREPARE_TASKS_TOOL_NOTE",
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
