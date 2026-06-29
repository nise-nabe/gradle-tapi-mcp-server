package com.example.gradle.mcp.protocol

data class ProgressResponseOptions(
    val includeProgress: Boolean = false,
    val includeProblems: Boolean = false,
) {
    companion object {
        const val MAX_COMPLETED_TASKS_IN_RESPONSE = 20
        const val MAX_RECENT_EVENTS_IN_RESPONSE = 10
        const val MAX_PROBLEMS_IN_RESPONSE = 20

        fun fromArgs(args: Map<String, Any>): ProgressResponseOptions =
            ProgressResponseOptions(
                includeProgress = args.optionalBoolean("includeProgress", default = false),
                includeProblems = args.optionalBoolean("includeProblems", default = false),
            )
    }
}
