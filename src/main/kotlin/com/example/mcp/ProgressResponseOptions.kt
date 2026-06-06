package com.example.mcp

data class ProgressResponseOptions(
    val includeProgress: Boolean = false,
) {
    companion object {
        const val MAX_COMPLETED_TASKS_IN_RESPONSE = 20
        const val MAX_RECENT_EVENTS_IN_RESPONSE = 10

        fun fromArgs(args: Map<String, Any>): ProgressResponseOptions =
            ProgressResponseOptions(
                includeProgress = args.optionalBoolean("includeProgress", default = false),
            )
    }
}

internal fun optionalProgressFields(
    progressOptions: ProgressResponseOptions,
    snapshot: BuildProgressSnapshot,
): Map<String, Any?> =
    buildMap {
        if (progressOptions.includeProgress) {
            put("progress", snapshot.toResponseMap())
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

private fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        else -> default
    }
