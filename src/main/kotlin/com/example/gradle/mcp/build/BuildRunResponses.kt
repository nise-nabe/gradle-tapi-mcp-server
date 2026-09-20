package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import java.io.File

/**
 * Response-map builders for gradle_run_* / gradle_cancel_build / queue fields.
 * Pure functions: callers resolve queue fields under the project lock.
 */

internal fun cancellationRequestedResponse(buildId: String): Map<String, Any?> =
    mapOf(
        "buildId" to buildId,
        "status" to BuildProgressTracker.STATUS_RUNNING,
        "message" to
            "Cancellation requested. Poll gradle_get_build_status until status is no longer running.",
    )

internal fun queuedCancelledResponse(buildId: String, record: BuildRecord): Map<String, Any?> =
    buildMap {
        put("buildId", buildId)
        put("status", BuildProgressTracker.STATUS_NOT_RUNNING)
        put("terminalStatus", BuildProgressTracker.STATUS_CANCELLED)
        put("cancelled", true)
        put("outcome", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_CANCELLED))
        record.finishedAt?.toString()?.let { put("finishedAt", it) }
        put("message", "Queued build cancelled.")
    }

internal fun alreadyFinishedCancelResponse(
    buildId: String,
    record: BuildRecord,
    status: String,
): Map<String, Any?> =
    buildMap {
        put("buildId", buildId)
        put("status", BuildProgressTracker.STATUS_NOT_RUNNING)
        put("terminalStatus", status)
        put("cancelled", false)
        put("outcome", BuildOutputParser.outcomeFromStatus(status))
        record.finishedAt?.toString()?.let { put("finishedAt", it) }
        put("message", "Build already finished; nothing to cancel.")
    }

internal fun detachedForegroundResponse(record: BuildRecord, request: BuildRunRequest): Map<String, Any?> =
    buildMap {
        put("buildId", record.id)
        put("status", BuildProgressTracker.STATUS_RUNNING)
        put("kind", request.kind.name.lowercase())
        put("tasks", record.tasks)
        put("testClasses", record.testClasses)
        putTestRunSelection(record.selection)
        putTaskPathInferredIfNeeded(record.taskPathInferred)
        put("detached", true)
        put(
            "message",
            "MCP client request ended; build continues in background. " +
                "Poll gradle_get_build_status with this buildId.",
        )
        put(
            "hint",
            "Use background: true for builds that may exceed ~30s. Poll without includeOutput until terminal; " +
                "on failure read testFailures/buildSummary before enabling includeOutput.",
        )
    }

internal fun runningBackgroundResponse(buildId: String, request: BuildRunRequest): Map<String, Any?> =
    buildMap {
        put("buildId", buildId)
        put("status", BuildProgressTracker.STATUS_RUNNING)
        put("kind", request.kind.name.lowercase())
        put("tasks", request.tasks)
        put("testClasses", request.testClasses)
        putTestRunSelection(request.selection)
        putTaskPathInferredIfNeeded(request.taskPathInferred)
        put(
            "message",
            "Build started in background. Poll gradle_get_build_status with this buildId.",
        )
    }

internal fun queuedBackgroundResponse(
    buildId: String,
    request: BuildRunRequest,
    queuePosition: Int?,
    queuedBehindBuildId: String?,
): Map<String, Any?> =
    buildMap {
        put("buildId", buildId)
        put("status", BuildProgressTracker.STATUS_QUEUED)
        put("kind", request.kind.name.lowercase())
        put("tasks", request.tasks)
        put("testClasses", request.testClasses)
        putTestRunSelection(request.selection)
        putTaskPathInferredIfNeeded(request.taskPathInferred)
        queuePosition?.let { put("queuePosition", it) }
        queuedBehindBuildId?.let { put("queuedBehindBuildId", it) }
        put(
            "message",
            "Build queued. Poll gradle_get_build_status with this buildId until status is running or terminal.",
        )
    }

/**
 * Rejects a buildId scoped to a different project than [hint]. Both cancel and
 * status paths apply the same ownership check before touching the record.
 */
internal fun requireMatchingProject(
    buildId: String,
    record: BuildRecord?,
    hint: File?,
) {
    if (hint == null) {
        return
    }
    val recordProject = record?.projectDirectory
    if (record != null && recordProject != null &&
        !ProjectDirectoryResolver.sameProject(recordProject, hint)
    ) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Build $buildId does not belong to project ${hint.path}",
        )
    }
}
