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
}
