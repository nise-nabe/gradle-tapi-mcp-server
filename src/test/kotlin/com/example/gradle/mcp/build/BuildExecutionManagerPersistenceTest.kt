package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.encodeMcpJson
import com.example.gradle.mcp.support.failedTracker
import com.example.gradle.mcp.support.gradleBuildResult
import com.example.gradle.mcp.support.mcpBuildResult
import com.example.gradle.mcp.support.persistedBuildManager
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.withWorkspaceDirectory
import com.example.gradle.mcp.support.writeDiskFile
import com.example.gradle.mcp.support.writeGradleResultToDisk
import com.example.gradle.mcp.support.writeMcpResultToDisk
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

class BuildExecutionManagerPersistenceTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `status returns not_found for unknown build`() {
        val result = manager.status("missing-build-id", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status loads persisted build from project gradle directory`(@TempDir projectDir: File) {
        val (persistedManager, store) = persistedBuildManager(projectDir)
        val buildId = "disk-only-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                buildSummary = mapOf("resultLine" to "BUILD SUCCESSFUL in 1s"),
            ),
        )

        val result = persistedManager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["buildId"] shouldBe buildId
    }

    @Test
    fun `status loads persisted build from workspace env when disconnected`(@TempDir projectDir: File) {
        val store = com.example.gradle.mcp.build.persistence.BuildRecordStore()
        val manager = BuildExecutionManager(GradleConnectionManager(), store)
        val buildId = "workspace-disk-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(buildId = buildId, projectDirectory = projectDir.absolutePath),
        )

        withWorkspaceDirectory(projectDir) {
            val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

            result["status"] shouldBe "succeeded"
            result["buildId"] shouldBe buildId
        }
    }

    @Test
    fun `status rejects path traversal buildId`(@TempDir projectDir: File) {
        val (manager, _) = persistedBuildManager(projectDir)
        val leakedDir = File(projectDir, ".gradle/leaked-build")
        leakedDir.mkdirs()
        File(leakedDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
                mcpBuildResult(buildId = "leaked", projectDirectory = projectDir.absolutePath),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.status("../leaked-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status prefers disk gradle succeeded over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-merge-build"
        val (manager, store) = persistedBuildManager(projectDir)
        val streams = CapturingStreams().also {
            it.appendStdoutForTests("partial\nBUILD SUCCESSFUL in 2s\n2 actionable tasks: 2 executed\n")
        }
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = failedTracker(message = "Gradle connection closed"),
                streams = streams,
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                errorMessage = "Gradle connection closed"
            },
        )
        store.writeDiskFile(projectDir, buildId, McpBuildRecordPaths.STDOUT_LOG, "partial\n")
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "succeeded",
                finishedAt = "2026-06-14T10:02:00Z",
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["statusSource"] shouldBe "disk"
        result.containsKey("error") shouldBe false
        result.containsKey("failedTaskCount") shouldBe false
        result.containsKey("failedTasks") shouldBe false
        (result["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 2s"
        (result["buildSummary"] as Map<*, *>)["taskSummaryLine"] shouldBe "2 actionable tasks: 2 executed"

        val withOutput = manager.status(
            buildId,
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )
        withOutput["stdoutTotalChars"] shouldBe streams.stdoutSnapshot().totalChars
        (withOutput["stdout"] as String) shouldContain "BUILD SUCCESSFUL in 2s"
    }

    @Test
    fun `status prefers disk gradle running over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-still-running"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = failedTracker(message = "Gradle connection closed"),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                errorMessage = "Gradle connection closed"
            },
        )
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "disk"
        result["liveProgress"] shouldBe false
        result.containsKey("error") shouldBe false
    }

    @Test
    fun `status loads disk build from explicit projectDirectory when connected elsewhere`(@TempDir projectDirs: File) {
        val projectA = projectDirs.resolve("project-a").also { it.mkdirs() }
        val projectB = projectDirs.resolve("project-b").also { it.mkdirs() }
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectB)
        val store = com.example.gradle.mcp.build.persistence.BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val buildId = "cross-project-build"
        store.writeMcpResultToDisk(
            projectA,
            mcpBuildResult(buildId = buildId, projectDirectory = projectA.absolutePath),
        )

        manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())["status"] shouldBe "not_found"

        val withHint = manager.status(
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(),
            projectDirectoryHint = projectA,
        )
        withHint["status"] shouldBe "succeeded"
        withHint["buildId"] shouldBe buildId
    }

    @Test
    fun `status omits stdout on disk running poll even when includeOutput is true`(@TempDir projectDir: File) {
        val buildId = "disk-running-no-stdout"
        val (manager, store) = persistedBuildManager(projectDir)
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
        )

        val result = manager.status(
            buildId,
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "disk"
        result.containsKey("stdout") shouldBe false
        result.containsKey("stderr") shouldBe false
    }

    @Test
    fun `status merges disk progress while in-memory build is still running`(@TempDir projectDir: File) {
        val buildId = "active-running-build"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = runningTracker(),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                finishedAt = Instant.now().minusSeconds(120).toString(),
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            """{"ts":"2026-06-14T10:00:30Z","type":"TASK_START","displayName":":app:build"}""" + "\n",
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        val withProgress = manager.status(
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeProgress = true),
        )

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "memory"
        result.containsKey("error") shouldBe false
        result["recordDirectory"].shouldNotBeNull()
        (withProgress["progress"] as Map<*, *>)["totalEventCount"] shouldBe 1
    }
}
