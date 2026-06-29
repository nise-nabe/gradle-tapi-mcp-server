package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker

internal fun optionalProgressFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeProgress) {
            put("progress", snapshot.toResponseMap(includeDownloads = progressOptions.includeDownloads))
        }
    }

internal fun optionalDownloadFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeDownloads && !progressOptions.includeProgress) {
            putAll(snapshot.downloadResponseFields())
        }
    }

internal fun terminalFailureFields(snapshot: BuildProgressSnapshot): Map<String, Any?> =
    if (snapshot.status == BuildProgressTracker.STATUS_RUNNING) {
        emptyMap()
    } else {
        buildMap {
            put("failedTaskCount", snapshot.failedTaskCount)
            put("failedTasks", snapshot.failedTasks)
            if (snapshot.status == BuildProgressTracker.STATUS_FAILED && snapshot.problems.isNotEmpty()) {
                put("problems", ProblemsSerializer.toResponseMaps(snapshot.problems))
            }
        }
    }

internal fun BuildProgressSnapshot.toResponseMap(includeDownloads: Boolean = false): Map<String, Any?> =
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
                    mapOf(
                        "timestamp" to event.timestamp,
                        "eventType" to event.eventType,
                        "displayName" to event.displayName,
                        "outcome" to event.outcome,
                    )
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
