package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.support.failedTracker
import com.example.gradle.mcp.support.mcpBuildResult
import com.example.gradle.mcp.support.persistedBuildManager
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.succeededTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.writeMcpResultToDisk
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class BuildExecutionManagerListBuildsTest {
    @Test
    fun `listBuilds returns memory and disk builds sorted by recency`(@TempDir projectDir: File) {
        val (manager, store) = persistedBuildManager(projectDir)
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = "disk-only-build",
                projectDirectory = projectDir.absolutePath,
                tasks = listOf("check"),
                startedAt = "2026-06-14T08:00:00Z",
                finishedAt = "2026-06-14T08:01:00Z",
            ),
        )

        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "memory-build",
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = succeededTracker(),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:01:00Z")
            },
        )

        val result = manager.listBuilds(projectDir, limit = 10)
        val builds = result["builds"] as List<*>

        result["projectDirectory"] shouldBe projectDir.absolutePath
        result["totalAvailable"] shouldBe 2
        result["truncated"] shouldBe false
        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf("memory-build", "disk-only-build")
        (builds[0] as Map<*, *>)["recordSource"] shouldBe "memory"
        (builds[1] as Map<*, *>)["recordSource"] shouldBe "disk"
    }

    @Test
    fun `listBuilds prefers memory record over disk for same buildId`(@TempDir projectDir: File) {
        val buildId = "shared-build"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = failedTracker(message = "still running in memory view"),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(buildId = buildId, projectDirectory = projectDir.absolutePath),
        )

        val builds = (manager.listBuilds(projectDir, limit = 10)["builds"] as List<Map<*, *>>)

        builds.single()["status"] shouldBe "failed"
        builds.single()["recordSource"] shouldBe "memory"
    }

    @Test
    fun `listBuilds applies limit and truncated flag`(@TempDir projectDir: File) {
        val (manager, store) = persistedBuildManager(projectDir)
        repeat(3) { index ->
            val finishedAt = "2026-06-14T10:0${index}:00Z"
            store.writeMcpResultToDisk(
                projectDir,
                mcpBuildResult(
                    buildId = "build-$index",
                    projectDirectory = projectDir.absolutePath,
                    startedAt = finishedAt,
                    finishedAt = finishedAt,
                ),
            )
        }

        val result = manager.listBuilds(projectDir, limit = 2)
        val builds = result["builds"] as List<*>

        builds.size shouldBe 2
        result["totalAvailable"] shouldBe 3
        result["truncated"] shouldBe true
    }

    @Test
    fun `listBuilds ranks disk builds by persisted timestamps not file mtime`(@TempDir projectDir: File) {
        val (manager, store) = persistedBuildManager(projectDir)

        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "memory-build",
                startedAt = Instant.parse("2026-06-14T08:00:00Z"),
                tracker = succeededTracker(),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T08:01:00Z")
            },
        )

        val newerDiskId = "newer-disk-build"
        val newerRecordDir = store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = newerDiskId,
                projectDirectory = projectDir.absolutePath,
                tasks = listOf("check"),
                startedAt = "2026-06-14T12:00:00Z",
                finishedAt = "2026-06-14T12:01:00Z",
            ),
        )
        File(newerRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
            .setLastModified(Instant.parse("2026-06-14T01:00:00Z").toEpochMilli())

        val staleDiskId = "stale-disk-build"
        val staleRecordDir = store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = staleDiskId,
                projectDirectory = projectDir.absolutePath,
                startedAt = "2026-06-14T06:00:00Z",
                finishedAt = "2026-06-14T06:01:00Z",
            ),
        )
        File(staleRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
            .setLastModified(Instant.parse("2026-06-14T23:00:00Z").toEpochMilli())

        val builds = (manager.listBuilds(projectDir, limit = 1)["builds"] as List<*>)

        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf(newerDiskId)
        (builds.single() as Map<*, *>)["recordSource"] shouldBe "disk"
    }

    @Test
    fun `listBuilds without projectDirectory returns all in-memory builds across projects`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectA)
        val manager = BuildExecutionManager(connectionManager)

        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "build-a",
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = runningTracker("Gradle tasks: build"),
                projectDirectory = projectA.absolutePath,
            ),
        )
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "build-b",
                kind = BuildKind.TASKS,
                tasks = listOf("check"),
                startedAt = Instant.parse("2026-06-14T09:00:00Z"),
                tracker = runningTracker("Gradle tasks: check"),
                projectDirectory = projectB.absolutePath,
            ),
        )

        val result = manager.listBuilds(projectDirectoryHint = null, limit = 10)
        val builds = result["builds"] as List<*>

        result["projectDirectory"] shouldBe projectA.absolutePath
        result["totalAvailable"] shouldBe 2
        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf("build-a", "build-b")
    }
}
