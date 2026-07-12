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

    @Test
    fun `running tasks clear for failed task finish display names`() {
        val accumulator = ProgressEventAccumulator()
        accumulator.apply(ProgressEventTypes.TASK_START, "Task :app:broken started")
        accumulator.apply(ProgressEventTypes.TASK_FAIL, "Task :app:broken failed")

        val snapshot = accumulator.snapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            recentEvents = emptyList(),
            totalEventCount = 2,
        )

        snapshot.runningTasks shouldBe emptyList()
        snapshot.failedTasks shouldBe listOf(":app:broken")
        snapshot.failedTaskCount shouldBe 1
    }

    @Test
    fun `test failures are tracked separately from Gradle task failures`() {
        val events = listOf(
            DiskBuildEvent("2026-06-14T10:00:00Z", ProgressEventTypes.TEST_FAIL, "Test com.example.FooTest.bar failed",
                outcome = "assertion failed",
                testDetails = TestProgressDetailsSnapshot(
                    className = "com.example.FooTest",
                    methodName = "bar",
                ),
            ),
            DiskBuildEvent("2026-06-14T10:00:30Z", ProgressEventTypes.TEST_FAIL, "Test class com.example.FooTest failed"),
            DiskBuildEvent("2026-06-14T10:01:00Z", ProgressEventTypes.TEST_FAIL, "Test Gradle Test Executor 1 failed"),
            DiskBuildEvent("2026-06-14T10:01:30Z", ProgressEventTypes.TEST_FAIL, "Test Gradle Test Run :plugin:test failed"),
            DiskBuildEvent("2026-06-14T10:02:00Z", ProgressEventTypes.TASK_FAIL, "Task :plugin:test failed"),
        )

        val snapshot = DiskBuildProgress.snapshotFromEvents(
            events = events,
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = "Gradle tasks: test",
        )

        snapshot.failedTaskCount shouldBe 5
        snapshot.failedGradleTaskCount shouldBe 1
        snapshot.failedGradleTasks shouldBe listOf(":plugin:test")
        snapshot.failedTestCount shouldBe 1
        snapshot.failedTestNames shouldBe listOf("com.example.FooTest.bar")
    }

    @Test
    fun `running tests clear when Gradle test display names include spaces`() {
        val accumulator = ProgressEventAccumulator()
        accumulator.apply(ProgressEventTypes.TEST_START, "Test com.example.FooTest.my method name")
        accumulator.apply(ProgressEventTypes.TEST_SUCCESS, "Test com.example.FooTest.my method name")

        val snapshot = accumulator.snapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = null,
            recentEvents = emptyList(),
            totalEventCount = 2,
        )

        snapshot.runningTasks shouldBe emptyList()
        snapshot.completedTasks shouldBe listOf("com.example.FooTest.my method name")
    }
}
