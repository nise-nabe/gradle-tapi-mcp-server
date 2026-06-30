package com.example.gradle.mcp.support

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.BuildStatusAssembler
import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.DiskBuildEvent
import com.example.gradle.mcp.build.persistence.GradleBuildResult
import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.build.persistence.McpBuildResult
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
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
    startedAt: String = TEST_ISO_START,
    finishedAt: String = TEST_ISO_FINISH,
    status: String = "succeeded",
    outcome: String? = "SUCCESS",
    error: String? = null,
    buildSummary: Map<String, Any?>? = null,
    failedTaskCount: Int = 0,
    failedTasks: List<String> = emptyList(),
    problems: List<BuildProblemSnapshot> = emptyList(),
    stdoutTotalChars: Int = 0,
    stderrTotalChars: Int = 0,
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
        failedTaskCount = failedTaskCount,
        failedTasks = failedTasks,
        problems = problems,
        stdoutTotalChars = stdoutTotalChars,
        stderrTotalChars = stderrTotalChars,
    )

internal fun gradleBuildResult(
    buildId: String,
    status: String,
    startedAt: String = TEST_ISO_START,
    finishedAt: String? = null,
    taskNames: List<String> = emptyList(),
    failure: String? = null,
): GradleBuildResult =
    GradleBuildResult(
        buildId = buildId,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        taskNames = taskNames,
        failure = failure,
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

internal fun BuildRecordStore.writeRecordFile(
    projectDir: File,
    buildId: String,
    fileName: String,
    content: String,
): File {
    val recordDir = recordDirectory(projectDir, buildId).shouldNotBeNull()
    recordDir.mkdirs()
    File(recordDir, fileName).writeText(content, StandardCharsets.UTF_8)
    return recordDir
}

internal fun BuildRecordStore.writeMcpResultToDisk(projectDir: File, result: McpBuildResult): File =
    writeRecordFile(
        projectDir,
        result.buildId,
        McpBuildRecordPaths.MCP_RESULT_FILE,
        mcpObjectMapper().writeValueAsString(result),
    )

internal fun BuildRecordStore.writeGradleResultToDisk(
    projectDir: File,
    buildId: String,
    result: GradleBuildResult,
) {
    writeRecordFile(
        projectDir,
        buildId,
        McpBuildRecordPaths.GRADLE_RESULT_FILE,
        mcpObjectMapper().writeValueAsString(result),
    )
}

internal fun BuildRecordStore.writeDiskFile(
    projectDir: File,
    buildId: String,
    fileName: String,
    content: String,
) {
    writeRecordFile(projectDir, buildId, fileName, content)
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
