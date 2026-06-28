package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.build.persistence.McpBuildResult
import com.example.gradle.mcp.protocol.mcpObjectMapper
import com.example.gradle.mcp.connection.GradleConnectionManager
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

class BuildExecutionManagerListBuildsTest {
    @Test
    fun `listBuilds returns memory and disk builds sorted by recency`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)

        val diskOnlyId = "disk-only-build"
        val recordDir = store.recordDirectory(projectDir, diskOnlyId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = diskOnlyId,
                    kind = "tasks",
                    tasks = listOf("check"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T08:00:00Z",
                    finishedAt = "2026-06-14T08:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val memoryTracker = BuildProgressTracker()
        memoryTracker.markStarting("Gradle tasks: build")
        memoryTracker.markSucceeded()
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "memory-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = memoryTracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also { it.finishedAt = Instant.parse("2026-06-14T10:01:00Z") },
        )

        val result = manager.listBuilds(projectDir, limit = 10)
        val builds = result["builds"] as List<*>

        result["projectDirectory"] shouldBe projectDir.absolutePath
        result["totalAvailable"] shouldBe 2
        result["truncated"] shouldBe false
        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf("memory-build", diskOnlyId)
        (builds[0] as Map<*, *>)["recordSource"] shouldBe "memory"
        (builds[1] as Map<*, *>)["recordSource"] shouldBe "disk"
    }

    @Test
    fun `listBuilds prefers memory record over disk for same buildId`(@TempDir projectDir: File) {
        val buildId = "shared-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("still running in memory view")
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

        val result = manager.listBuilds(projectDir, limit = 10)
        val builds = result["builds"] as List<Map<*, *>>

        builds.single()["status"] shouldBe "failed"
        builds.single()["recordSource"] shouldBe "memory"
    }

    @Test
    fun `listBuilds applies limit and truncated flag`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        repeat(3) { index ->
            val buildId = "build-$index"
            val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
            recordDir.mkdirs()
            val finishedAt = "2026-06-14T10:0${index}:00Z"
            File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
                mcpObjectMapper().writeValueAsString(
                    McpBuildResult(
                        buildId = buildId,
                        kind = "tasks",
                        tasks = listOf("build"),
                        testClasses = emptyList(),
                        projectDirectory = projectDir.absolutePath,
                        startedAt = finishedAt,
                        finishedAt = finishedAt,
                        status = "succeeded",
                        outcome = "SUCCESS",
                    ),
                ),
                StandardCharsets.UTF_8,
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
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectDir)
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)

        val olderMemoryTracker = BuildProgressTracker()
        olderMemoryTracker.markStarting("Gradle tasks: build")
        olderMemoryTracker.markSucceeded()
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "memory-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T08:00:00Z"),
                progressTracker = olderMemoryTracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also { it.finishedAt = Instant.parse("2026-06-14T08:01:00Z") },
        )

        val newerDiskId = "newer-disk-build"
        val newerRecordDir = store.recordDirectory(projectDir, newerDiskId).shouldNotBeNull()
        newerRecordDir.mkdirs()
        val newerResultFile = File(newerRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
        newerResultFile.writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = newerDiskId,
                    kind = "tasks",
                    tasks = listOf("check"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T12:00:00Z",
                    finishedAt = "2026-06-14T12:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        newerResultFile.setLastModified(Instant.parse("2026-06-14T01:00:00Z").toEpochMilli())

        val staleDiskId = "stale-disk-build"
        val staleRecordDir = store.recordDirectory(projectDir, staleDiskId).shouldNotBeNull()
        staleRecordDir.mkdirs()
        val staleResultFile = File(staleRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
        staleResultFile.writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = staleDiskId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T06:00:00Z",
                    finishedAt = "2026-06-14T06:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        staleResultFile.setLastModified(Instant.parse("2026-06-14T23:00:00Z").toEpochMilli())

        val result = manager.listBuilds(projectDir, limit = 1)
        val builds = result["builds"] as List<*>

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
            BuildRecord(
                id = "build-a",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = BuildProgressTracker().also { it.markStarting("Gradle tasks: build") },
                streams = CapturingStreams(),
                projectDirectory = projectA.absolutePath,
            ),
        )
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "build-b",
                kind = BuildKind.TASKS,
                tasks = listOf("check"),
                startedAt = Instant.parse("2026-06-14T09:00:00Z"),
                progressTracker = BuildProgressTracker().also { it.markStarting("Gradle tasks: check") },
                streams = CapturingStreams(),
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
