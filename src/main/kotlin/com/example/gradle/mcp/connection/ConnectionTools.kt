package com.example.gradle.mcp.connection

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.protocol.McpBuildNotifier
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalProjectDirectoryProperty
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.rejectUnsupportedProjectPath
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.registerTool
import com.example.gradle.mcp.protocol.stringProperty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.StartEvent
import java.io.File
import kotlin.coroutines.coroutineContext

internal fun connectProject(
    runtime: GradleMcpRuntime,
    projectDirectory: File,
    config: ConnectionConfig,
    hooks: ConnectHooks = ConnectHooks(),
    session: SessionProjectContext? = null,
): Map<String, Any?> = ProjectLifecycleLock.withProjectLock(projectDirectory) {
    val activeBuild = runtime.buildExecutionManager.activeBuildSnapshot(projectDirectory)
    if (activeBuild != null) {
        throw McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Cannot connect while a Gradle build is active for ${projectDirectory.path}. " +
                "Wait for the build to finish, call gradle_cancel_build, or call gradle_disconnect.",
            errorDetails = activeBuild.toErrorFields(),
        )
    }
    val info = runtime.connectionManager.ensureConnected(config, hooks, holder = session)
    if (info.state == "connected") {
        session?.onConnected(projectDirectory)
    }
    info.toResponseMap()
}

/**
 * Disconnect semantics:
 * - explicit projectDirectory -> release just that project (session must know it),
 * - all=true -> close every pooled connection (requires no projectDirectory),
 * - neither -> release the session's default project only.
 *
 * The pooled connection is physically closed once its last session releases
 * it; when other sessions still hold it the response reports
 * `retainedByOtherSessions` and running builds are left alone.
 */
internal fun disconnectProjects(
    runtime: GradleMcpRuntime,
    projectDirectoryArg: String?,
    all: Boolean = false,
    session: SessionProjectContext? = null,
): Map<String, Any?> {
    if (projectDirectoryArg != null && all) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "projectDirectory and all are mutually exclusive for gradle_disconnect.",
        )
    }
    val disconnectEverything = all || (session == null && projectDirectoryArg == null)
    val projectDirectory = projectDirectoryArg?.let(ProjectDirectoryResolver::bestEffortDirectory)
        ?: if (!disconnectEverything) session?.defaultProject() else null

    if (!disconnectEverything && projectDirectory == null) {
        return mapOf("state" to "not_connected")
    }
    if (!disconnectEverything && session != null && !session.isKnown(projectDirectory!!)) {
        return mapOf(
            "projectDirectory" to projectDirectory.path,
            "state" to "not_connected",
        )
    }

    val hadActiveBuild: Boolean
    val disconnected = ProjectLifecycleLock.withLifecycleLock(projectDirectory) {
        if (disconnectEverything) {
            hadActiveBuild = runtime.buildExecutionManager.hasActiveBuild()
            runtime.buildExecutionManager.onDisconnect(null)
            session?.onDisconnected(null)
            runtime.connectionManager.disconnectAll()
        } else {
            val willClose = runtime.connectionManager.disconnectWouldClosePool(projectDirectory!!, session)
            hadActiveBuild = willClose && runtime.buildExecutionManager.hasActiveBuild(projectDirectory)
            if (willClose) {
                runtime.buildExecutionManager.onDisconnect(projectDirectory)
            }
            val info = runtime.connectionManager.disconnect(projectDirectory, session)
            session?.onDisconnected(projectDirectory)
            if (info == null) {
                emptyList()
            } else {
                listOf(info)
            }
        }
    }
    runtime.buildExecutionManager.wakeQueuedBuilds(projectDirectory)
    return buildMap {
        if (disconnected.isEmpty()) {
            put("state", "not_connected")
            if (!disconnectEverything) {
                put("projectDirectory", projectDirectory?.path)
            }
        } else if (disconnected.size == 1) {
            putAll(disconnected.single().toResponseMap())
        } else {
            put("state", "disconnected")
            put("projectDirectories", disconnected.map { it.projectDirectory })
        }
        if (hadActiveBuild) {
            put("warning", DISCONNECT_DURING_BUILD_WARNING)
        }
    }
}

private const val DISCONNECT_DURING_BUILD_WARNING =
    "One or more builds were still in progress for the disconnected project(s). The server cancelled them " +
        "via the Tooling API CancellationToken and marked status cancelled."

internal fun connectSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("projectDirectory"),
        properties = mapOf(
            "projectDirectory" to stringProperty("Gradle project root path"),
            "gradleUserHome" to stringProperty("Optional GRADLE_USER_HOME"),
            "gradleVersion" to stringProperty("Optional Gradle version to download"),
            "gradleInstallation" to stringProperty("Optional local Gradle installation"),
        ),
    )

internal fun connectionStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to optionalProjectDirectoryProperty(),
            "refresh" to booleanProperty(
                "When true, fetches BuildEnvironment for connected projects missing cached runtime stack. " +
                    "Default false (cache-only).",
            ),
        ),
    )

internal fun buildEnvironmentSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
        ),
    )

internal fun disconnectSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to optionalProjectDirectoryProperty(),
            "all" to booleanProperty(
                "Disconnect every connected project server-wide. Default false: " +
                    "omit to disconnect only the session default project.",
            ),
        ),
    )

internal fun connectionStatusPayload(
    runtime: GradleMcpRuntime,
    args: Map<String, Any>,
    session: SessionProjectContext? = null,
): Map<String, Any?> {
    val projectDirectory = args.optionalString("projectDirectory")
        ?.let(ProjectDirectoryResolver::bestEffortDirectory)
    val refresh = args.optionalBoolean("refresh", default = false)
    val isBuildActive: (File) -> Boolean = { directory ->
        runtime.buildExecutionManager.hasActiveBuild(directory)
    }
    if (session != null) {
        if (projectDirectory != null) {
            if (!session.isKnown(projectDirectory)) {
                return ConnectionStatus(
                    connected = false,
                    projectDirectory = projectDirectory.path,
                ).toResponseMap()
            }
            return runtime.connectionManager.status(projectDirectory, refresh, isBuildActive)
        }
        return runtime.connectionManager.statusForSession(session, refresh, isBuildActive)
    }
    return runtime.connectionManager.status(projectDirectory, refresh, isBuildActive)
}

internal fun buildEnvironmentPayload(
    runtime: GradleMcpRuntime,
    args: Map<String, Any>,
    session: SessionProjectContext? = null,
): Map<String, Any?> {
    rejectUnsupportedProjectPath(args, "gradle_get_build_environment")
    val projectDirectory =
        ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager, session)
    // The Tooling API connection is not thread-safe: serve the cached
    // snapshot without touching it, otherwise fetch under the same
    // no-active-build guard used by every other model query.
    runtime.connectionManager.cachedEnvironment(projectDirectory)?.let { return it.toMap() }
    return ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = runtime.buildExecutionManager,
        message = { directory ->
            "Cannot query the Gradle build environment while a build is active for ${directory.path}. " +
                "Wait for the build to finish, call gradle_cancel_build, or poll gradle_get_build_status."
        },
    ) {
        runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
            runtime.connectionManager.fetchAndCacheEnvironment(projectDirectory, connection).toMap()
        }
    }
}

private fun gradleDistributionDownloadListener(notifier: McpBuildNotifier): ProgressListener =
    ProgressListener { event ->
        if (event is StartEvent) {
            notifier.notifyLog(event.displayName, LoggingLevel.Info)
        }
    }

context(runtime: GradleMcpRuntime)
fun Server.registerConnectionTools(scope: CoroutineScope, session: SessionProjectContext? = null) {
    registerTool(
        scope,
        name = "gradle_connect",
        description = McpToolDescriptions.CONNECT,
        schema = connectSchema(),
    ) { args, notifier ->
        val projectDirectory = ProjectDirectoryResolver.canonicalDirectory(
            args.requiredString("projectDirectory"),
        )
        val config = ConnectionConfig(
            projectDirectory = projectDirectory.path,
            gradleUserHome = args.optionalString("gradleUserHome"),
            gradleVersion = args.optionalString("gradleVersion"),
            gradleInstallation = args.optionalString("gradleInstallation"),
        )
        // The first model call inside connect installs the Gradle
        // distribution when it is missing; wire cancellation to the MCP
        // request and surface FILE_DOWNLOAD events as log notifications.
        val cancellation = GradleConnector.newCancellationTokenSource()
        coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancellation.cancel()
            }
        }
        val hooks = ConnectHooks(
            cancellationToken = cancellation.token(),
            progressListener = notifier?.let(::gradleDistributionDownloadListener),
        )
        val response = connectProject(runtime, projectDirectory, config, hooks, session)
        jsonResult(response)
    }
    registerTool(
        scope,
        name = "gradle_connection_status",
        description = McpToolDescriptions.CONNECTION_STATUS,
        schema = connectionStatusSchema(),
    ) { args ->
        jsonResult(connectionStatusPayload(runtime, args, session))
    }
    registerTool(
        scope,
        name = "gradle_disconnect",
        description = McpToolDescriptions.DISCONNECT,
        schema = disconnectSchema(),
    ) { args ->
        jsonResult(
            disconnectProjects(
                runtime,
                projectDirectoryArg = args.optionalString("projectDirectory"),
                all = args.optionalBoolean("all", default = false),
                session = session,
            ),
        )
    }
    registerTool(
        scope,
        name = "gradle_get_build_environment",
        description = McpToolDescriptions.BUILD_ENVIRONMENT,
        schema = buildEnvironmentSchema(),
    ) { args ->
        jsonResult(buildEnvironmentPayload(runtime, args, session))
    }
}
