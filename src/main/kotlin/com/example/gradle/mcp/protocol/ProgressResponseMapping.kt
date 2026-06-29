package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker

internal fun optionalProgressFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeProgress) {
            put("progress", snapshot.toResponseMap())
        }
        if (progressOptions.includeProblems &&
            snapshot.status != BuildProgressTracker.STATUS_FAILED &&
            snapshot.liveProblems.isNotEmpty()
        ) {
            put("liveProblems", cappedLiveProblemResponse(snapshot.liveProblems))
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
            val problems = if (progressOptions.includeProblems) {
                ProblemsSerializer.mergedDistinct(snapshot.problems, snapshot.liveProblems)
            } else {
                snapshot.problems
            }
            if (snapshot.status == BuildProgressTracker.STATUS_FAILED && problems.isNotEmpty()) {
                put("problems", cappedTerminalProblemResponse(problems))
            }
        }
    }

private fun cappedLiveProblemResponse(problems: List<BuildProblemSnapshot>): List<Map<String, Any?>> =
    ProblemsSerializer.toResponseMaps(
        problems.takeLast(ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE),
    )

private fun cappedTerminalProblemResponse(problems: List<BuildProblemSnapshot>): List<Map<String, Any?>> =
    ProblemsSerializer.toResponseMaps(capTerminalProblems(problems))

internal fun capTerminalProblems(problems: List<BuildProblemSnapshot>): List<BuildProblemSnapshot> {
    val max = ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE
    if (problems.size <= max) {
        return problems
    }
    val errors = problems.filter { it.severity == "error" }
    val nonErrors = problems.filter { it.severity != "error" }
    return if (errors.size >= max) {
        errors.take(max)
    } else {
        errors + nonErrors.takeLast(max - errors.size)
    }
}

internal fun BuildProgressSnapshot.toResponseMap(): Map<String, Any?> =
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
                mapOf(
                    "timestamp" to event.timestamp,
                    "eventType" to event.eventType,
                    "displayName" to event.displayName,
                    "outcome" to event.outcome,
                )
            },
        "totalEventCount" to totalEventCount,
    )
