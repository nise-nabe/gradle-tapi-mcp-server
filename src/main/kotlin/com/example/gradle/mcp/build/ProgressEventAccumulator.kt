package com.example.gradle.mcp.build

internal class ProgressEventAccumulator {
    private val completedTasks = LinkedHashSet<String>()
    private val runningTasks = LinkedHashSet<String>()
    private val failedTasks = LinkedHashSet<String>()

    fun apply(eventType: String, displayName: String) {
        when (eventType) {
            ProgressEventTypes.TASK_START, ProgressEventTypes.TEST_START -> {
                runningTasks.add(displayName)
            }
            ProgressEventTypes.TASK_SUCCESS,
            ProgressEventTypes.TASK_SKIP,
            ProgressEventTypes.TEST_SUCCESS,
            ProgressEventTypes.TEST_SKIP,
            -> {
                runningTasks.remove(displayName)
                completedTasks.add(displayName)
            }
            ProgressEventTypes.TASK_FAIL, ProgressEventTypes.TEST_FAIL -> {
                runningTasks.remove(displayName)
                failedTasks.add(displayName)
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
        )
}
