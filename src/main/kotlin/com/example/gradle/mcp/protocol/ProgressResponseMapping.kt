package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildStatusView
import com.example.gradle.mcp.build.FailedTestSnapshot
import com.example.gradle.mcp.build.TestProgressDetailsSnapshot

internal fun optionalProgressFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
    statusSource: String = BuildStatusView.SOURCE_MEMORY,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeProgress) {
            val includeDownloads =
                progressOptions.includeDownloads && statusSource == BuildStatusView.SOURCE_MEMORY
            put("progress", snapshot.toResponseMap(progressOptions, includeDownloads))
        }
    }

internal fun optionalDownloadFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
    statusSource: String,
): Map<String, Any?> =
    buildMap {
        if (
            progressOptions.includeDownloads &&
            !progressOptions.includeProgress &&
            statusSource == BuildStatusView.SOURCE_MEMORY
        ) {
            putAll(snapshot.downloadResponseFields())
        }
    }

internal fun terminalFailureFields(
    snapshot: BuildProgressSnapshot,
    progressOptions: ProgressResponseOptions,
): Map<String, Any?> =
    if (snapshot.status == BuildProgressTracker.STATUS_RUNNING) {
        emptyMap()
    } else {
        buildMap {
            put("failedTaskCount", snapshot.failedTaskCount)
            put("failedTasks", snapshot.failedTasks)
            if (snapshot.status == BuildProgressTracker.STATUS_FAILED && snapshot.problems.isNotEmpty()) {
                put("problems", ProblemsSerializer.toResponseMaps(snapshot.problems))
            }
            if (progressOptions.includeTestDetails && shouldIncludeFailedTests(snapshot)) {
                val failedTests = snapshot.failedTests
                    .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)
                    .map { it.toResponseMap() }
                if (failedTests.isNotEmpty()) {
                    put("failedTests", failedTests)
                }
            }
        }
    }

private fun shouldIncludeFailedTests(snapshot: BuildProgressSnapshot): Boolean =
    snapshot.failedTests.isNotEmpty() &&
        (snapshot.status == BuildProgressTracker.STATUS_FAILED ||
            snapshot.status == BuildProgressTracker.STATUS_CANCELLED)

internal fun BuildProgressSnapshot.toResponseMap(
    progressOptions: ProgressResponseOptions,
    includeDownloads: Boolean = false,
): Map<String, Any?> =
    buildMap {
        put("status", status)
        put("currentOperation", currentOperation)
        put("completedTaskCount", completedTaskCount)
        put("runningTaskCount", runningTaskCount)
        put("failedTaskCount", failedTaskCount)
        put("completedTasks", completedTasks.takeLast(ProgressResponseOptions.MAX_COMPLETED_TASKS_IN_RESPONSE))
        put("runningTasks", runningTasks)
        put("failedTasks", failedTasks)
        put(
            "recentEvents",
            recentEvents
                .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)
                .map { event ->
                    buildMap<String, Any?> {
                        put("timestamp", event.timestamp)
                        put("eventType", event.eventType)
                        put("displayName", event.displayName)
                        put("outcome", event.outcome)
                        if (progressOptions.includeTestDetails) {
                            event.testDetails?.let { details -> put("test", details.toResponseMap()) }
                        }
                    }
                },
        )
        put("totalEventCount", totalEventCount)
        if (includeDownloads) {
            putAll(downloadResponseFields())
        }
    }

internal fun BuildProgressSnapshot.downloadResponseFields(): Map<String, Any?> =
    mapOf(
        "activeDownloadCount" to activeDownloadCount,
        "recentDownloads" to recentDownloads
            .takeLast(ProgressResponseOptions.MAX_RECENT_DOWNLOADS_IN_RESPONSE)
            .map { download ->
                buildMap<String, Any?> {
                    put("uri", download.uri)
                    put("status", download.status)
                    download.displayName?.let { put("displayName", it) }
                    download.bytesDownloaded?.let { put("bytesDownloaded", it) }
                }
            },
    )

private fun TestProgressDetailsSnapshot.toResponseMap(): Map<String, Any?> =
    buildMap {
        className?.let { put("className", it) }
        methodName?.let { put("methodName", it) }
        sourceType?.let { put("sourceType", it) }
        sourcePath?.let { put("sourcePath", it) }
        sourceLine?.let { put("sourceLine", it) }
        sourceColumn?.let { put("sourceColumn", it) }
        failureMessage?.let { put("failureMessage", it) }
    }

private fun FailedTestSnapshot.toResponseMap(): Map<String, Any?> =
    buildMap {
        put("displayName", displayName)
        className?.let { put("className", it) }
        methodName?.let { put("methodName", it) }
        failureMessage?.let { put("failureMessage", it) }
    }
