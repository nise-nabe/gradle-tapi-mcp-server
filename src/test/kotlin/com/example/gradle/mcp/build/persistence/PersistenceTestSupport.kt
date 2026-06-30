package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.BuildKind
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildRecord
import com.example.gradle.mcp.build.BuildStatusAssembler
import com.example.gradle.mcp.build.CapturingStreams
import com.example.gradle.mcp.build.seedNoopConnection
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.mcpObjectMapper
import io.kotest.matchers.nulls.shouldNotBeNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

internal fun mcpBuildResult(
    buildId: String,
    projectDirectory: String,
    kind: String = "tasks",
    tasks: List<String> = listOf("build"),
    testClasses: List<String> = emptyList(),
    startedAt: String = "2026-06-14T10:00:00Z",
    finishedAt: String = "2026-06-14T10:01:00Z",
    status: String = "succeeded",
    outcome: String = "SUCCESS",
    error: String? = null,
    buildSummary: Map<String, Any?>? = null,
): McpBuildResult =
    McpBuildResult(
        buildId = buildId,
        kind = kind,
        tasks = tasks,
        testClasses = testClasses,
        projectDirectory = projectDirectory,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status,
        outcome = outcome,
        error = error,
        buildSummary = buildSummary,
    )

internal fun gradleBuildResult(
    buildId: String,
    status: String,
    startedAt: String = "2026-06-14T10:00:00Z",
    finishedAt: String? = null,
    taskNames: List<String> = emptyList(),
): GradleBuildResult =
    GradleBuildResult(
        buildId = buildId,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        taskNames = taskNames,
    )

internal fun diskBuildEvent(timestamp: String, type: String, displayName: String): DiskBuildEvent =
    DiskBuildEvent(
        timestamp = timestamp,
        eventType = type,
        displayName = displayName,
    )

internal fun contractMcpResult(status: String, finishedAt: Instant): McpBuildResult =
    mcpBuildResult(
        buildId = "build-1",
        projectDirectory = "/tmp/project",
        finishedAt = finishedAt.toString(),
        status = status,
        outcome = "FAILED",
        error = "Gradle connection closed",
    )

internal fun BuildRecordStore.writeMcpResultToDisk(projectDir: File, result: McpBuildResult): File {
    val recordDir = recordDirectory(projectDir, result.buildId).shouldNotBeNull()
    recordDir.mkdirs()
    File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
        mcpObjectMapper().writeValueAsString(result),
        StandardCharsets.UTF_8,
    )
    return recordDir
}

internal fun BuildRecordStore.writeGradleResultToDisk(
    projectDir: File,
    buildId: String,
    result: GradleBuildResult,
) {
    val recordDir = recordDirectory(projectDir, buildId).shouldNotBeNull()
    recordDir.mkdirs()
    File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
        mcpObjectMapper().writeValueAsString(result),
        StandardCharsets.UTF_8,
    )
}

internal fun BuildRecordStore.writeDiskFile(
    projectDir: File,
    buildId: String,
    fileName: String,
    content: String,
) {
    val recordDir = recordDirectory(projectDir, buildId).shouldNotBeNull()
    recordDir.mkdirs()
    File(recordDir, fileName).writeText(content, StandardCharsets.UTF_8)
}

internal fun persistedBuildManager(
    projectDir: File,
    store: BuildRecordStore = BuildRecordStore(),
): Pair<BuildExecutionManager, BuildRecordStore> {
    val connectionManager = GradleConnectionManager()
    connectionManager.seedNoopConnection(projectDir)
    return BuildExecutionManager(connectionManager, store) to store
}

internal fun BuildRecordStore.loadAssembledStatus(
    projectDir: File,
    buildId: String,
    outputLimit: OutputLimitOptions = OutputLimitOptions(),
    progressOptions: ProgressResponseOptions = ProgressResponseOptions(),
): Map<String, Any?>? {
    val artifacts = loadArtifacts(projectDir, buildId) ?: return null
    return BuildStatusAssembler.assemble(
        PersistedBuildViewFactory.fromArtifacts(buildId, artifacts),
        outputLimit,
        progressOptions,
    )
}

internal fun completedBuildRecord(
    projectDir: File,
    buildId: String,
    stdout: String = "BUILD SUCCESSFUL in 1s\n",
): BuildRecord {
    val streams = CapturingStreams().also { it.appendStdoutForTests(stdout) }
    val tracker = BuildProgressTracker().also {
        it.markStarting("Gradle tasks: build")
        it.markSucceeded()
    }
    return BuildRecord(
        id = buildId,
        kind = BuildKind.TASKS,
        tasks = listOf("build"),
        startedAt = Instant.parse("2026-06-14T10:00:00Z"),
        progressTracker = tracker,
        streams = streams,
        projectDirectory = projectDir.absolutePath,
    ).also { it.finishedAt = Instant.parse("2026-06-14T10:01:00Z") }
}
