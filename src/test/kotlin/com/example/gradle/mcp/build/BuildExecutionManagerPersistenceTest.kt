package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.GradleBuildResult
import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.build.persistence.McpBuildResult
import com.example.gradle.mcp.protocol.mcpObjectMapper
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.support.withWorkspaceDirectory
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
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
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val persistedManager = BuildExecutionManager(connectionManager, store)
        val buildId = "disk-only-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                    buildSummary = mapOf("resultLine" to "BUILD SUCCESSFUL in 1s"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = persistedManager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["buildId"] shouldBe buildId
    }

    @Test
    fun `status loads persisted build from workspace env when disconnected`(@TempDir projectDir: File) {
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(GradleConnectionManager(), store)
        val buildId = "workspace-disk-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        withWorkspaceDirectory(projectDir) {
            val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

            result["status"] shouldBe "succeeded"
            result["buildId"] shouldBe buildId
        }
    }

    @Test
    fun `status rejects path traversal buildId`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val leakedDir = File(projectDir, ".gradle/leaked-build")
        leakedDir.mkdirs()
        File(leakedDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = "leaked",
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        val manager = BuildExecutionManager(connectionManager, BuildRecordStore())

        val result = manager.status("../leaked-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status prefers disk gradle succeeded over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-merge-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")
        val streams = CapturingStreams()
        streams.appendStdoutForTests(
            "partial\nBUILD SUCCESSFUL in 2s\n2 actionable tasks: 2 executed\n",
        )
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = streams,
                projectDirectory = projectDir.absolutePath,
            ).also {
                it.finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                it.errorMessage = "Gradle connection closed"
            },
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(
            "partial\n",
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
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
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also {
                it.finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                it.errorMessage = "Gradle connection closed"
            },
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
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
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val buildId = "cross-project-build"
        val recordDir = store.recordDirectory(projectA, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectA.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val withoutHint = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        withoutHint["status"] shouldBe "not_found"

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
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
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
    fun `status skips disk merge while in-memory build is still running`(@TempDir projectDir: File) {
        val buildId = "active-running-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val staleFinishedAt = Instant.now().minusSeconds(120).toString()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = staleFinishedAt,
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """{"ts":"2026-06-14T10:00:30Z","type":"TASK_START","displayName":":app:build"}""" + "\n",
            StandardCharsets.UTF_8,
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "memory"
        result.containsKey("error") shouldBe false
    }
}
