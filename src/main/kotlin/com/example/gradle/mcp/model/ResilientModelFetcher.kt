package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.util.GradleVersion
import java.util.concurrent.atomic.AtomicReference

internal enum class ModelFetchPhase {
    PROJECTS_LOADED,
    BUILD_FINISHED,
}

internal data class FailureSnapshot(
    val message: String?,
    val description: String?,
    val causes: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    fun dedupeKey(): String =
        listOf(
            message.orEmpty(),
            description.orEmpty(),
            causes.joinToString(),
            problems.joinToString(),
        ).joinToString("|")

    fun toResponseMap(): Map<String, Any?> =
        buildMap {
            message?.let { put("message", it) }
            description?.let { put("description", it) }
            if (causes.isNotEmpty()) {
                put("causes", causes)
            }
            if (problems.isNotEmpty()) {
                put("problems", problems)
            }
        }
}

internal data class ResilientModel<T : Any>(
    val model: T,
    val failures: List<FailureSnapshot> = emptyList(),
    val failuresTruncated: Boolean = false,
    val usedFallback: Boolean = false,
) {
    val partial: Boolean get() = failures.isNotEmpty()
}

internal data class ProjectAndInvocations(
    val project: GradleProject,
    val invocations: BuildInvocations,
)

internal object ResilientModelFetcher {
    internal val minResilientGradleVersion: GradleVersion = GradleVersion.version("9.3")

    fun supportsResilientFetch(gradleVersion: String?): Boolean {
        if (gradleVersion.isNullOrBlank()) {
            return true
        }
        return try {
            GradleVersion.version(gradleVersion).baseVersion >= minResilientGradleVersion
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    fun <T : Any> fetch(
        connection: ProjectConnection,
        modelType: Class<T>,
        phase: ModelFetchPhase,
        prepareTasks: List<String>,
        gradleVersion: String?,
    ): ResilientModel<T> {
        if (!supportsResilientFetch(gradleVersion)) {
            return fallbackGetModel(connection, modelType, prepareTasks)
        }
        val holder = AtomicReference<ResilientModelPayload?>()
        val thrown = runPhasedAction(
            connection = connection,
            prepareTasks = prepareTasks,
            register = { builder, handler ->
                val action = FetchToolingModelAction(modelType)
                when (phase) {
                    ModelFetchPhase.PROJECTS_LOADED -> builder.projectsLoaded(action, handler)
                    ModelFetchPhase.BUILD_FINISHED -> builder.buildFinished(action, handler)
                }
            },
            onComplete = holder::set,
        )
        val payload = holder.get()
        if (payload == null) {
            return missingPayloadFallback(
                thrown = thrown,
                gradleVersion = gradleVersion,
                modelName = modelType.simpleName,
            ) {
                fallbackGetModel(connection, modelType, prepareTasks)
            }
        }
        val snapshots = mergeSnapshots(payload.failures.map { it.toSnapshot() }, thrown, payload.isFailuresTruncated)
        val model = castFetchedModel(payload.model, modelType, modelType.simpleName)
            ?: throw modelFetchFailed(modelType.simpleName, thrown, snapshots.items, snapshots.truncated)
        return ResilientModel(
            model = model,
            failures = snapshots.items,
            failuresTruncated = snapshots.truncated,
        )
    }

    fun fetchProjectAndInvocations(
        connection: ProjectConnection,
        prepareTasks: List<String>,
        gradleVersion: String?,
    ): ResilientModel<ProjectAndInvocations> {
        if (!supportsResilientFetch(gradleVersion)) {
            return fallbackProjectAndInvocations(connection, prepareTasks)
        }
        val holder = AtomicReference<ResilientProjectInvocationsPayload?>()
        val thrown = runPhasedAction(
            connection = connection,
            prepareTasks = prepareTasks,
            register = { builder, handler ->
                builder.buildFinished(FetchProjectAndInvocationsAction(), handler)
            },
            onComplete = holder::set,
        )
        val payload = holder.get()
        if (payload == null) {
            return missingPayloadFallback(
                thrown = thrown,
                gradleVersion = gradleVersion,
                modelName = "GradleProject",
            ) {
                fallbackProjectAndInvocations(connection, prepareTasks)
            }
        }
        val snapshots = mergeSnapshots(payload.failures.map { it.toSnapshot() }, thrown, payload.isFailuresTruncated)
        val project = castFetchedModel(payload.project, GradleProject::class.java, "GradleProject")
        val invocations = castFetchedModel(payload.invocations, BuildInvocations::class.java, "BuildInvocations")
        if (project == null || invocations == null) {
            val missing = buildList {
                if (project == null) add("GradleProject")
                if (invocations == null) add("BuildInvocations")
            }.joinToString(" and ")
            throw modelFetchFailed(missing, thrown, snapshots.items, snapshots.truncated)
        }
        return ResilientModel(
            model = ProjectAndInvocations(project, invocations),
            failures = snapshots.items,
            failuresTruncated = snapshots.truncated,
        )
    }

    private fun <T : Any> fallbackGetModel(
        connection: ProjectConnection,
        modelType: Class<T>,
        prepareTasks: List<String>,
    ): ResilientModel<T> =
        try {
            ResilientModel(
                model = connection.fetchModel(modelType, prepareTasks),
                usedFallback = true,
            )
        } catch (exception: GradleConnectionException) {
            val snapshots = snapshotsFromException(exception)
            throw modelFetchFailed(modelType.simpleName, exception, snapshots.items, snapshots.truncated)
        }

    private fun fallbackProjectAndInvocations(
        connection: ProjectConnection,
        prepareTasks: List<String>,
    ): ResilientModel<ProjectAndInvocations> =
        try {
            ResilientModel(
                model = ProjectAndInvocations(
                    project = connection.fetchModel(GradleProject::class.java, prepareTasks),
                    invocations = connection.fetchModel(BuildInvocations::class.java, prepareTasks),
                ),
                usedFallback = true,
            )
        } catch (exception: GradleConnectionException) {
            val snapshots = snapshotsFromException(exception)
            throw modelFetchFailed("GradleProject", exception, snapshots.items, snapshots.truncated)
        }

    private fun <T : Any> missingPayloadFallback(
        thrown: GradleConnectionException?,
        gradleVersion: String?,
        modelName: String,
        fallback: () -> ResilientModel<T>,
    ): ResilientModel<T> {
        if (thrown != null && gradleVersion.isNullOrBlank()) {
            return fallback()
        }
        val snapshots = snapshotsFromException(thrown)
        throw modelFetchFailed(modelName, thrown, snapshots.items, snapshots.truncated)
    }

    private fun <T> runPhasedAction(
        connection: ProjectConnection,
        prepareTasks: List<String>,
        register: (BuildActionExecuter.Builder, IntermediateResultHandler<T>) -> BuildActionExecuter.Builder,
        onComplete: (T) -> Unit,
    ): GradleConnectionException? {
        val handler = IntermediateResultHandler(onComplete)
        val executer = register(connection.action(), handler).build()
        if (prepareTasks.isNotEmpty()) {
            executer.forTasks(*prepareTasks.toTypedArray())
        }
        return try {
            executer.run()
            null
        } catch (exception: GradleConnectionException) {
            exception
        }
    }
}

internal fun <T : Any> ProjectConnection.fetchResilientModel(
    modelType: Class<T>,
    phase: ModelFetchPhase,
    prepareTasks: List<String>,
    gradleVersion: String?,
): ResilientModel<T> =
    ResilientModelFetcher.fetch(this, modelType, phase, prepareTasks, gradleVersion)

internal fun ProjectConnection.fetchResilientProjectAndInvocations(
    prepareTasks: List<String>,
    gradleVersion: String?,
): ResilientModel<ProjectAndInvocations> =
    ResilientModelFetcher.fetchProjectAndInvocations(this, prepareTasks, gradleVersion)

internal fun attachResilientMetadata(
    body: Map<String, Any?>,
    result: ResilientModel<*>,
): Map<String, Any?> {
    if (!result.partial) {
        return body
    }
    return body + buildMap {
        put("partial", true)
        put("failures", result.failures.map { it.toResponseMap() })
        if (result.failuresTruncated) {
            put("failuresTruncated", true)
        }
    }
}

internal fun FailureRecord.toSnapshot(): FailureSnapshot =
    FailureSnapshot(
        message = message,
        description = description,
        causes = causeMessages,
        problems = problems,
    )

internal fun modelFetchFailed(
    modelName: String,
    exception: GradleConnectionException?,
    failures: List<FailureSnapshot>,
    truncated: Boolean,
): McpException {
    val detail = exception?.message?.takeIf { it.isNotBlank() } ?: "no model was produced"
    return McpException(
        McpErrorCode.BUILD_FAILED,
        "Failed to fetch $modelName: $detail",
        exception,
        errorDetails = resilientErrorDetails(failures, truncated),
    )
}

internal fun resilientErrorDetails(
    failures: List<FailureSnapshot>,
    truncated: Boolean,
): Map<String, Any?> =
    buildMap {
        if (failures.isNotEmpty()) {
            put("failures", failures.map { it.toResponseMap() })
        }
        if (truncated) {
            put("failuresTruncated", true)
        }
    }

private data class SnapshotList(
    val items: List<FailureSnapshot>,
    val truncated: Boolean,
)

private fun mergeSnapshots(
    payloadFailures: List<FailureSnapshot>,
    thrown: GradleConnectionException?,
    payloadTruncated: Boolean,
): SnapshotList {
    val extra = snapshotsFromException(thrown)
    val seen = LinkedHashSet<String>()
    val merged = ArrayList<FailureSnapshot>()
    (payloadFailures + extra.items).forEach { snapshot ->
        if (seen.add(snapshot.dedupeKey())) {
            merged.add(snapshot)
        }
    }
    val capped = merged.take(FailureRecords.MAX_FAILURES)
    return SnapshotList(
        items = capped,
        truncated = payloadTruncated || extra.truncated || merged.size > FailureRecords.MAX_FAILURES,
    )
}

private fun snapshotsFromException(exception: GradleConnectionException?): SnapshotList {
    if (exception == null) {
        return SnapshotList(emptyList(), truncated = false)
    }
    val slice = FailureRecords.fromFailures(exception.failures)
    return SnapshotList(slice.records.map { it.toSnapshot() }, slice.isTruncated)
}

private fun <T : Any> castFetchedModel(value: Any?, type: Class<T>, modelName: String): T? {
    if (value == null) {
        return null
    }
    if (!type.isInstance(value)) {
        throw McpException(
            McpErrorCode.INTERNAL_ERROR,
            "Unexpected ${value.javaClass.name} when fetching $modelName",
        )
    }
    return type.cast(value)
}
