package com.example.gradle.mcp.protocol

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
