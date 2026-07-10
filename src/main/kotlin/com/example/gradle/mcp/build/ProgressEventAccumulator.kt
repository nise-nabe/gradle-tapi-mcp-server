package com.example.gradle.mcp.build

internal class ProgressEventAccumulator {
    private val completedTasks = LinkedHashSet<String>()
    private val runningTasks = LinkedHashSet<String>()
    private val failedTasks = LinkedHashSet<String>()

    fun apply(eventType: String, displayName: String) {
        val key = TaskProgressKey.fromDisplayName(displayName)
        when (eventType) {
            ProgressEventTypes.TASK_START, ProgressEventTypes.TEST_START -> {
                runningTasks.add(key)
            }
            ProgressEventTypes.TASK_SUCCESS,
            ProgressEventTypes.TASK_SKIP,
            ProgressEventTypes.TEST_SUCCESS,
            ProgressEventTypes.TEST_SKIP,
            -> {
                runningTasks.remove(key)
                completedTasks.add(key)
            }
            ProgressEventTypes.TASK_FAIL, ProgressEventTypes.TEST_FAIL -> {
                runningTasks.remove(key)
                failedTasks.add(key)
            }
        }
    }

    fun clearRunning() {
        runningTasks.clear()
    }

    fun snapshot(
        status: String,
        currentOperation: String?,
        recentEvents: List<ProgressEventSnapshot>,
        totalEventCount: Int,
        problems: List<BuildProblemSnapshot> = emptyList(),
        liveProblems: List<BuildProblemSnapshot> = emptyList(),
        recentDownloads: List<DownloadProgressSnapshot> = emptyList(),
        activeDownloadCount: Int = 0,
        failedTests: List<FailedTestSnapshot> = emptyList(),
    ): BuildProgressSnapshot =
        BuildProgressSnapshot(
            status = status,
            currentOperation = currentOperation ?: recentEvents.lastOrNull()?.displayName,
            completedTaskCount = completedTasks.size,
            runningTaskCount = runningTasks.size,
            failedTaskCount = failedTasks.size,
            completedTasks = completedTasks.toList(),
            runningTasks = runningTasks.toList(),
            failedTasks = failedTasks.toList(),
            recentEvents = recentEvents,
            totalEventCount = totalEventCount,
            problems = problems,
            liveProblems = liveProblems,
            recentDownloads = recentDownloads,
            activeDownloadCount = activeDownloadCount,
            failedTests = failedTests,
        )
}

internal object TaskProgressKey {
    private val taskDisplayPattern = Regex("^Task (\\S+)")
    private val testDisplayPattern = Regex("^Test (\\S+)")

    fun fromDisplayName(displayName: String): String =
        taskDisplayPattern.find(displayName)?.groupValues?.get(1)
            ?: testDisplayPattern.find(displayName)?.groupValues?.get(1)
            ?: displayName
}
