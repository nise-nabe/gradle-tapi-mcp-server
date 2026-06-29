package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker

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
            if (progressOptions.includeTestDetails && snapshot.status == BuildProgressTracker.STATUS_FAILED) {
                val failedTests = snapshot.failedTests
                    .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)
                    .map { failedTest ->
                        buildMap<String, Any?> {
                            put("displayName", failedTest.displayName)
                            failedTest.className?.let { put("className", it) }
                            failedTest.methodName?.let { put("methodName", it) }
                            failedTest.failureMessage?.let { put("failureMessage", it) }
                        }
                    }
                if (failedTests.isNotEmpty()) {
                    put("failedTests", failedTests)
                }
            }
        }
    }

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
                        event.testDetails?.let { details ->
                            put(
                                "test",
                                buildMap<String, Any?> {
                                    details.className?.let { put("className", it) }
                                    details.methodName?.let { put("methodName", it) }
                                    details.sourceType?.let { put("sourceType", it) }
                                    details.sourcePath?.let { put("sourcePath", it) }
                                    details.sourceLine?.let { put("sourceLine", it) }
                                    details.sourceColumn?.let { put("sourceColumn", it) }
                                    details.failureMessage?.let { put("failureMessage", it) }
                                },
                            )
                        }
                    }
                }
            },
        "totalEventCount" to totalEventCount,
    )
