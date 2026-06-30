package com.example.gradle.mcp.build

data class TestProgressDetailsSnapshot(
    val className: String? = null,
    val methodName: String? = null,
    val sourceType: String? = null,
    val sourcePath: String? = null,
    val sourceLine: Int? = null,
    val sourceColumn: Int? = null,
    val failureMessage: String? = null,
)

data class FailedTestSnapshot(
    val className: String? = null,
    val methodName: String? = null,
    val displayName: String,
    val failureMessage: String? = null,
) {
    fun stableKey(): String = listOf(className.orEmpty(), methodName.orEmpty(), displayName).joinToString("|")
}

data class ProgressEventSnapshot(
    val timestamp: String,
    val eventType: String,
    val displayName: String,
    val outcome: String? = null,
    val testDetails: TestProgressDetailsSnapshot? = null,
) {
    fun toFailedTestSnapshot(): FailedTestSnapshot? =
        if (eventType != ProgressEventTypes.TEST_FAIL) {
            null
        } else {
            FailedTestSnapshots.fromTestFailure(displayName, outcome, testDetails)
        }
}

data class BuildProgressSnapshot(
    val status: String,
    val currentOperation: String?,
    val completedTaskCount: Int,
    val runningTaskCount: Int,
    val failedTaskCount: Int,
    val completedTasks: List<String>,
    val runningTasks: List<String>,
    val failedTasks: List<String>,
    val recentEvents: List<ProgressEventSnapshot>,
    val totalEventCount: Int,
    val problems: List<BuildProblemSnapshot> = emptyList(),
    val failedTests: List<FailedTestSnapshot> = emptyList(),
)
