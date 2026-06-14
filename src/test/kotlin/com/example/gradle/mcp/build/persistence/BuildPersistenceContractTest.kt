package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.ProgressEventTypes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class BuildPersistenceContractTest {
    @Test
    fun `isStaleGradleRunning is false when events are empty`() {
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", Instant.now()),
            emptyList(),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when build finished event exists`() {
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", Instant.now()),
            listOf(event("2026-06-14T10:02:00Z", ProgressEventTypes.BUILD_FINISHED, "Build finished")),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false immediately after disconnect with only pre-finalize events`() {
        val finishedAt = Instant.now().minusSeconds(5)
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", finishedAt),
            listOf(
                event("2026-06-14T10:00:01Z", ProgressEventTypes.START, "Gradle tasks: build"),
                event("2026-06-14T10:00:02Z", ProgressEventTypes.TASK_START, ":app:compileJava"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when post-finalize events exist`() {
        val finishedAt = Instant.parse("2026-06-14T10:01:00Z")
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", finishedAt),
            listOf(
                event("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:build"),
                event("2026-06-14T10:01:05Z", ProgressEventTypes.TASK_SUCCESS, ":app:build"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning compares instants with mixed fractional precision`() {
        val finishedAt = Instant.parse("2026-06-14T10:01:00.100Z")
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", finishedAt),
            listOf(
                event("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:build"),
                event("2026-06-14T10:01:00.200Z", ProgressEventTypes.TASK_SUCCESS, ":app:build"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when heartbeat continues after mcp finalize`() {
        val finishedAt = Instant.now().minusSeconds(120)
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", finishedAt),
            listOf(
                event("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:longTask"),
                event(finishedAt.plusSeconds(30).toString(), ProgressEventTypes.HEARTBEAT, "Gradle build active"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is true after grace with only pre-finalize events`() {
        val finishedAt = Instant.now().minusSeconds(120)
        BuildPersistenceContract.isStaleGradleRunning(
            mcpResult("failed", finishedAt),
            listOf(event(finishedAt.minusSeconds(30).toString(), ProgressEventTypes.TASK_START, ":app:build")),
        ) shouldBe true
    }

    @Test
    fun `resolve prefers gradle running over recent mcp failed after disconnect`() {
        val finishedAt = Instant.now().minusSeconds(5).toString()
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = GradleBuildResult(
                buildId = "disconnect",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                taskNames = listOf("build"),
            ),
            mcpResult = McpBuildResult(
                buildId = "disconnect",
                kind = "tasks",
                tasks = listOf("build"),
                testClasses = emptyList(),
                projectDirectory = "/tmp/project",
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = finishedAt,
                status = BuildProgressTracker.STATUS_FAILED,
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
            events = listOf(
                event("2026-06-14T10:00:01Z", ProgressEventTypes.TASK_START, ":app:compileJava"),
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_RUNNING
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.NONE
    }

    @Test
    fun `resolve prefers gradle terminal over stale mcp failed`() {
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = GradleBuildResult(
                buildId = "gradle-wins",
                status = BuildProgressTracker.STATUS_SUCCEEDED,
                finishedAt = "2026-06-14T10:02:00Z",
            ),
            mcpResult = McpBuildResult(
                buildId = "gradle-wins",
                kind = "tasks",
                tasks = listOf("build"),
                testClasses = emptyList(),
                projectDirectory = "/tmp/project",
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = "2026-06-14T10:01:00Z",
                status = BuildProgressTracker.STATUS_FAILED,
                outcome = "FAILED",
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.GRADLE
    }

    private fun mcpResult(status: String, finishedAt: Instant): McpBuildResult =
        McpBuildResult(
            buildId = "build-1",
            kind = "tasks",
            tasks = listOf("build"),
            testClasses = emptyList(),
            projectDirectory = "/tmp/project",
            startedAt = "2026-06-14T10:00:00Z",
            finishedAt = finishedAt.toString(),
            status = status,
            outcome = "FAILED",
            error = "Gradle connection closed",
        )

    private fun event(timestamp: String, type: String, displayName: String): DiskBuildEvent =
        DiskBuildEvent(
            timestamp = timestamp,
            eventType = type,
            displayName = displayName,
        )
}
