package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.FailedTestSnapshot
import com.example.gradle.mcp.build.TestProgressDetailsSnapshot

internal fun optionalProgressFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeProgress) {
            put("progress", snapshot.toResponseMap(progressOptions))
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
): Map<String, Any?> =
    mapOf(
        "status" to status,
        "currentOperation" to currentOperation,
        "completedTaskCount" to completedTaskCount,
        "runningTaskCount" to runningTaskCount,
        "failedTaskCount" to failedTaskCount,
        "completedTasks" to completedTasks.takeLast(ProgressResponseOptions.MAX_COMPLETED_TASKS_IN_RESPONSE),
        "runningTasks" to runningTasks,
        "failedTasks" to failedTasks,
        "recentEvents" to recentEvents
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
        "totalEventCount" to totalEventCount,
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
