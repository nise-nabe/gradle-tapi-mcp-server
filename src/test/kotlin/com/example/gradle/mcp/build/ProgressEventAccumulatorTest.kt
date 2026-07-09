package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.DiskBuildEvent
import com.example.gradle.mcp.build.persistence.DiskBuildProgress
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProgressEventAccumulatorTest {
    @Test
    fun `disk and accumulator produce the same snapshot for task events`() {
        val events = listOf(
            DiskBuildEvent("2026-06-14T10:00:00Z", ProgressEventTypes.TASK_START, ":app:compile"),
            DiskBuildEvent("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_SUCCESS, ":app:compile"),
            DiskBuildEvent("2026-06-14T10:01:00Z", ProgressEventTypes.TASK_START, ":app:broken"),
            DiskBuildEvent("2026-06-14T10:01:30Z", ProgressEventTypes.TASK_FAIL, ":app:broken", "broken"),
        )

        val diskSnapshot = DiskBuildProgress.snapshotFromEvents(
            events = events,
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = "Gradle tasks: build",
        )
        val accumulator = ProgressEventAccumulator()
        for (event in events) {
            accumulator.apply(event.eventType, event.displayName)
        }
        val directSnapshot = accumulator.snapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = "Gradle tasks: build",
            recentEvents = events.map { it.toProgressEvent() },
            totalEventCount = events.size,
        )

        diskSnapshot shouldBe directSnapshot
        diskSnapshot.failedTaskCount shouldBe 1
        diskSnapshot.failedTasks shouldBe listOf(":app:broken")
    }

    @Test
    fun `running tasks clear when Gradle start and finish display names differ`() {
        val accumulator = ProgressEventAccumulator()
        accumulator.apply(ProgressEventTypes.TASK_START, "Task :app:compile started")
        accumulator.apply(ProgressEventTypes.TASK_SUCCESS, "Task :app:compile UP-TO-DATE")

        val snapshot = accumulator.snapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = null,
            recentEvents = emptyList(),
            totalEventCount = 2,
        )

        snapshot.runningTasks shouldBe emptyList()
        snapshot.completedTasks shouldBe listOf(":app:compile")
        snapshot.runningTaskCount shouldBe 0
        snapshot.completedTaskCount shouldBe 1
    }
}
