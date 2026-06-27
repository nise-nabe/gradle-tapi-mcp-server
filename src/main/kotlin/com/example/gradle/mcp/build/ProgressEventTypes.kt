package com.example.gradle.mcp.build

/**
 * Event type strings shared between the Gradle init script (`events.ndjson`),
 * [ProgressEventAccumulator], and [BuildProgressTracker].
 */
internal object ProgressEventTypes {
    const val START = "START"
    const val FINISH = "FINISH"
    const val FAIL = "FAIL"
    const val CANCEL = "CANCEL"
    const val HEARTBEAT = "HEARTBEAT"
    const val BUILD_FINISHED = "BUILD_FINISHED"

    const val TASK_START = "TASK_START"
    const val TASK_SUCCESS = "TASK_SUCCESS"
    const val TASK_SKIP = "TASK_SKIP"
    const val TASK_FAIL = "TASK_FAIL"

    const val TEST_START = "TEST_START"
    const val TEST_SUCCESS = "TEST_SUCCESS"
    const val TEST_SKIP = "TEST_SKIP"
    const val TEST_FAIL = "TEST_FAIL"

    const val ROOT_FINISH = "ROOT_FINISH"

    const val CONFIG_START = "CONFIG_START"
    const val CONFIG_FINISH = "CONFIG_FINISH"
    const val CONFIG_FAIL = "CONFIG_FAIL"

    val NON_ACTIONABLE: Set<String> = setOf(START, HEARTBEAT, BUILD_FINISHED)
}
