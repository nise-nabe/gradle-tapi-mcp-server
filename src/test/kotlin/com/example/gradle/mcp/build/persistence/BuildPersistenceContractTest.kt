package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.support.contractMcpResult
import com.example.gradle.mcp.support.diskBuildEvent
import com.example.gradle.mcp.support.gradleBuildResult
import com.example.gradle.mcp.support.mcpBuildResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class BuildPersistenceContractTest {
    @Test
    fun `isStaleGradleRunning is false when events are empty`() {
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", Instant.now()),
            emptyList(),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when build finished event exists`() {
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", Instant.now()),
            listOf(diskBuildEvent("2026-06-14T10:02:00Z", ProgressEventTypes.BUILD_FINISHED, "Build finished")),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false immediately after disconnect with only pre-finalize events`() {
        val finishedAt = Instant.now().minusSeconds(5)
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", finishedAt),
            listOf(
                diskBuildEvent("2026-06-14T10:00:01Z", ProgressEventTypes.START, "Gradle tasks: build"),
                diskBuildEvent("2026-06-14T10:00:02Z", ProgressEventTypes.TASK_START, ":app:compileJava"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when post-finalize events exist`() {
        val finishedAt = Instant.parse("2026-06-14T10:01:00Z")
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", finishedAt),
            listOf(
                diskBuildEvent("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:build"),
                diskBuildEvent("2026-06-14T10:01:05Z", ProgressEventTypes.TASK_SUCCESS, ":app:build"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning compares instants with mixed fractional precision`() {
        val finishedAt = Instant.parse("2026-06-14T10:01:00.100Z")
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", finishedAt),
            listOf(
                diskBuildEvent("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:build"),
                diskBuildEvent("2026-06-14T10:01:00.200Z", ProgressEventTypes.TASK_SUCCESS, ":app:build"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is false when heartbeat continues after mcp finalize`() {
        val finishedAt = Instant.now().minusSeconds(120)
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", finishedAt),
            listOf(
                diskBuildEvent("2026-06-14T10:00:30Z", ProgressEventTypes.TASK_START, ":app:longTask"),
                diskBuildEvent(finishedAt.plusSeconds(30).toString(), ProgressEventTypes.HEARTBEAT, "Gradle build active"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isStaleGradleRunning is true after grace with only pre-finalize events`() {
        val finishedAt = Instant.now().minusSeconds(120)
        BuildPersistenceContract.isStaleGradleRunning(
            contractMcpResult("failed", finishedAt),
            listOf(diskBuildEvent(finishedAt.minusSeconds(30).toString(), ProgressEventTypes.TASK_START, ":app:build")),
        ) shouldBe true
    }

    @Test
    fun `resolve prefers gradle running over recent mcp failed after disconnect`() {
        val finishedAt = Instant.now().minusSeconds(5).toString()
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = gradleBuildResult(
                buildId = "disconnect",
                status = BuildProgressTracker.STATUS_RUNNING,
                taskNames = listOf("build"),
            ),
            mcpResult = mcpBuildResult(
                buildId = "disconnect",
                projectDirectory = "/tmp/project",
                finishedAt = finishedAt,
                status = BuildProgressTracker.STATUS_FAILED,
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
            events = listOf(
                diskBuildEvent("2026-06-14T10:00:01Z", ProgressEventTypes.TASK_START, ":app:compileJava"),
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_RUNNING
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.NONE
    }

    @Test
    fun `resolve prefers gradle terminal over stale mcp failed`() {
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = gradleBuildResult(
                buildId = "gradle-wins",
                status = BuildProgressTracker.STATUS_SUCCEEDED,
                finishedAt = "2026-06-14T10:02:00Z",
            ),
            mcpResult = mcpBuildResult(
                buildId = "gradle-wins",
                projectDirectory = "/tmp/project",
                status = BuildProgressTracker.STATUS_FAILED,
                outcome = "FAILED",
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.GRADLE
    }

    @Test
    fun `resolve treats gradle cancelled as terminal`() {
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = gradleBuildResult(
                buildId = "cancelled-build",
                status = BuildProgressTracker.STATUS_CANCELLED,
                finishedAt = "2026-06-14T10:02:00Z",
            ),
            mcpResult = mcpBuildResult(
                buildId = "cancelled-build",
                projectDirectory = "/tmp/project",
                status = BuildProgressTracker.STATUS_CANCELLED,
                outcome = "CANCELLED",
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_CANCELLED
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.GRADLE
    }

    @Test
    fun `resolve uses mcp cancelled when gradle result is absent`() {
        val resolved = BuildPersistenceContract.resolve(
            gradleResult = null,
            mcpResult = mcpBuildResult(
                buildId = "mcp-cancelled",
                projectDirectory = "/tmp/project",
                status = BuildProgressTracker.STATUS_CANCELLED,
                outcome = "CANCELLED",
            ),
        )

        resolved.status shouldBe BuildProgressTracker.STATUS_CANCELLED
        resolved.terminalSource shouldBe BuildPersistenceContract.TerminalStatusSource.MCP
    }
}
