package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildStatusView

internal object PersistedBuildViewFactory {
    fun fromArtifacts(
        buildId: String,
        artifacts: PersistedBuildArtifacts,
    ): BuildStatusView {
        val events = artifacts.events
        val resolved = BuildPersistenceContract.resolve(
            artifacts.gradleResult,
            artifacts.mcpResult,
            events,
        )
        val status = resolved.status
        val terminalSource = resolved.terminalSource
        val isRunning = status == BuildProgressTracker.STATUS_RUNNING
        val progressFromEvents = when {
            isRunning && DiskBuildProgress.hasActionableProgress(events) -> {
                diskProgressFromEvents(artifacts, events, status)
            }
            !isRunning &&
                terminalSource == BuildPersistenceContract.TerminalStatusSource.GRADLE &&
                status == BuildProgressTracker.STATUS_FAILED &&
                DiskBuildProgress.hasActionableProgress(events) -> {
                diskProgressFromEvents(artifacts, events, status)
            }
            else -> {
                null
            }
        }
        val progressFromMcp = if (!isRunning && terminalSource == BuildPersistenceContract.TerminalStatusSource.MCP) {
            artifacts.mcpResult?.let { mcp ->
                BuildProgressSnapshot(
                    status = status,
                    currentOperation = null,
                    completedTaskCount = 0,
                    runningTaskCount = 0,
                    failedTaskCount = mcp.failedTaskCount,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = mcp.failedTasks,
                    recentEvents = emptyList(),
                    totalEventCount = 0,
                    problems = mcp.problems,
                )
            }
        } else {
            null
        }
        val progress = progressFromEvents ?: progressFromMcp
        val progressAvailable = progress != null

        return BuildStatusView(
            buildId = buildId,
            kind = artifacts.mcpResult?.kind,
            status = status,
            startedAt = artifacts.mcpResult?.startedAt ?: artifacts.gradleResult?.startedAt,
            finishedAt = if (isRunning) {
                artifacts.gradleResult?.finishedAt
            } else {
                artifacts.gradleResult?.finishedAt ?: artifacts.mcpResult?.finishedAt
            },
            tasks = artifacts.mcpResult?.tasks?.takeIf { it.isNotEmpty() }
                ?: artifacts.gradleResult?.taskNames.orEmpty(),
            testClasses = artifacts.mcpResult?.testClasses.orEmpty(),
            error = BuildPersistenceContract.resolveError(
                artifacts.gradleResult,
                artifacts.mcpResult,
                terminalSource,
            ),
            outcome = BuildOutputParser.outcomeFromStatus(status),
            buildSummary = if (!isRunning) {
                BuildPersistenceContract.terminalBuildSummary(artifacts, terminalSource)
            } else {
                null
            },
            progress = progress,
            progressAvailable = progressAvailable,
            stdout = artifacts.stdout,
            stderr = artifacts.stderr,
            statusSource = BuildStatusView.SOURCE_DISK,
            recordDirectory = artifacts.recordDir.absolutePath,
        )
    }

    private fun diskProgressFromEvents(
        artifacts: PersistedBuildArtifacts,
        events: List<DiskBuildEvent>,
        status: String,
    ): BuildProgressSnapshot =
        DiskBuildProgress.snapshotFromEvents(
            events = events,
            status = status,
            currentOperation = artifacts.gradleResult?.taskNames?.let { names ->
                if (names.isEmpty()) null else "Gradle tasks: ${names.joinToString()}"
            },
        )
}
