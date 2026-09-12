package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.build.buildStatusPayload
import com.example.gradle.mcp.build.listBuildsPayload
import com.example.gradle.mcp.connection.buildEnvironmentPayload
import com.example.gradle.mcp.connection.connectionStatusPayload
import com.example.gradle.mcp.model.projectOverviewPayload
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal val registeredMcpResourceTemplateUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

internal data class GradleTapiResourceTemplateSpec(
    val name: String,
    val uriTemplate: String,
    val description: String,
)

internal object GradleTapiResourceTemplates {
    private const val ROOT = "${GradleTapiResourceUri.SCHEME}://{${GradleTapiResourceUri.PROJECT_ROOT_VARIABLE}}"

    val connectionStatus = GradleTapiResourceTemplateSpec(
        name = "gradle-connection-status",
        uriTemplate = "$ROOT/connection/status",
        description = "Connection snapshot for one project (gradle_connection_status). " +
            "?refresh=true fetches BuildEnvironment when the cache is empty.",
    )
    val environment = GradleTapiResourceTemplateSpec(
        name = "gradle-build-environment",
        uriTemplate = "$ROOT/environment",
        description = "Resolved Gradle/Java BuildEnvironment (gradle_get_build_environment).",
    )
    val overview = GradleTapiResourceTemplateSpec(
        name = "gradle-project-overview",
        uriTemplate = "$ROOT/overview",
        description = "Module tree and task counts (gradle_get_project_overview). " +
            "Optional ?projectPath= or ?buildTreePath=.",
    )
    val buildStatus = GradleTapiResourceTemplateSpec(
        name = "gradle-build-status",
        uriTemplate = "$ROOT/builds/{buildId}/status",
        description = "MCP build poll (gradle_get_build_status). Default omits stdout and progress.",
    )
    val recentBuilds = GradleTapiResourceTemplateSpec(
        name = "gradle-builds-recent",
        uriTemplate = "$ROOT/builds/recent",
        description = "Recent MCP builds from memory and disk (gradle_list_builds). No Tooling API.",
    )

    val all: List<GradleTapiResourceTemplateSpec> = listOf(
        connectionStatus,
        environment,
        overview,
        buildStatus,
        recentBuilds,
    )

    fun concreteResources(projectDirectory: File): List<Resource> {
        val kinds = listOf(
            connectionStatus to GradleTapiResourceKind.ConnectionStatus,
            environment to GradleTapiResourceKind.Environment,
            overview to GradleTapiResourceKind.Overview,
            recentBuilds to GradleTapiResourceKind.RecentBuilds,
        )
        return kinds.map { (spec, kind) ->
            Resource(
                uri = GradleTapiResourceUri(projectDirectory, kind).toUri(),
                name = spec.name,
                description = spec.description,
                mimeType = GradleTapiResourceUri.JSON_MIME_TYPE,
            )
        }
    }
}

/**
 * Phase 1 MCP resources wrap existing tool payloads. They do **not** replace tools.
 *
 * ## ProjectLifecycleGuard
 *
 * **Reject** (no stale snapshot): `gradle_get_project_overview` already throws
 * `BUILD_ALREADY_RUNNING` with `activeBuildId` when an MCP build is active for
 * that project. The overview resource shares that handler, so a live
 * GradleProject fetch is refused rather than returning a cached tree.
 *
 * Connection status, environment, build status, and recent builds follow the
 * corresponding tools (no extra resource-only gate). Status/list are memory/disk;
 * environment always fetches `BuildEnvironment` like `gradle_get_build_environment`.
 *
 * Subscribe / `listChanged` are off in Phase 1.
 */
context(runtime: GradleMcpRuntime)
fun Server.registerGradleTapiResources() {
    registeredMcpResourceTemplateUris.clear()
    GradleTapiResourceTemplates.all.forEach { spec ->
        registeredMcpResourceTemplateUris.add(spec.uriTemplate)
        addResourceTemplate(
            uriTemplate = spec.uriTemplate,
            name = spec.name,
            description = spec.description,
            mimeType = GradleTapiResourceUri.JSON_MIME_TYPE,
        ) { request, _ ->
            readGradleTapiResourceResult(runtime, request.uri)
        }
    }
    runtime.connectionManager.connectedProjectDirectories().forEach { project ->
        GradleTapiResourceTemplates.concreteResources(project).forEach { resource ->
            addResource(
                uri = resource.uri,
                name = resource.name,
                description = resource.description ?: "",
                mimeType = resource.mimeType ?: GradleTapiResourceUri.JSON_MIME_TYPE,
            ) { request ->
                readGradleTapiResourceResult(runtime, request.uri)
            }
        }
    }
}

internal suspend fun readGradleTapiResourceResult(
    runtime: GradleMcpRuntime,
    uri: String,
): ReadResourceResult =
    try {
        withContext(Dispatchers.IO) {
            jsonResourceResult(uri, readGradleTapiResource(runtime, uri))
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw exception.toSdkResourceException()
    }

/**
 * Dispatches `resources/read` to the same payload functions as the wrapped tools.
 */
internal fun readGradleTapiResource(
    runtime: GradleMcpRuntime,
    uri: String,
): Map<String, Any?> {
    val parsed = GradleTapiResourceUri.parse(uri)
    val args = parsed.toToolArgs()
    return when (parsed.kind) {
        GradleTapiResourceKind.ConnectionStatus -> connectionStatusPayload(runtime, args)
        GradleTapiResourceKind.Environment -> buildEnvironmentPayload(runtime, args)
        GradleTapiResourceKind.Overview -> projectOverviewPayload(runtime, args)
        GradleTapiResourceKind.RecentBuilds -> listBuildsPayload(runtime, args)
        is GradleTapiResourceKind.BuildStatus -> buildStatusPayload(runtime, args)
    }
}
