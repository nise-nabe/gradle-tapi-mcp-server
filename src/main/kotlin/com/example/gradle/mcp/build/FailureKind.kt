package com.example.gradle.mcp.build

enum class FailureKind {
    TEST_FAILURE,
    TASK_FAILURE,
    CONNECTION_FAILURE,
    CANCELLED,
    ;

    /** Stable agent-facing category (avoids string-matching task names). */
    val category: String
        get() = when (this) {
            TEST_FAILURE -> "TEST"
            TASK_FAILURE -> "GRADLE_TASK"
            CONNECTION_FAILURE -> "TOOLING_CONNECTION"
            CANCELLED -> "CANCELLED"
        }
}
