package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildFailureClassifier
import com.example.gradle.mcp.build.BuildStatusView
import com.example.gradle.mcp.build.FailureKind
import com.example.gradle.mcp.protocol.ProblemsSerializer

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
                    failedGradleTaskCount = mcp.failedGradleTaskCount,
                    failedTestCount = mcp.failedTestCount,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = mcp.failedTasks,
                    failedGradleTasks = mcp.failedGradleTasks,
                    failedTestNames = mcp.failedTestNames,
                    recentEvents = emptyList(),
                    totalEventCount = 0,
                    problems = mcp.problems,
                    failedTests = mcp.testFailures,
                )
            }
        } else {
            null
        }
        val progress = attachPersistedProblems(
            progress = progressFromEvents
                ?: mergeEventDerivedProgress(progressFromMcp, artifacts, events, status),
            mcpResult = artifacts.mcpResult,
            status = status,
            isRunning = isRunning,
        )
        val progressAvailable = progress != null
        val rawError = BuildPersistenceContract.resolveError(
            artifacts.gradleResult,
            artifacts.mcpResult,
            terminalSource,
        )
        val classified = BuildFailureClassifier.classify(
            status = status,
            kind = artifacts.mcpResult?.kind,
            error = rawError,
            progress = progress,
            stdout = artifacts.stdout.text,
        )

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
            selection = artifacts.mcpResult?.selection,
            error = classified.error,
            failureKind = classified.failureKind
                ?: artifacts.mcpResult?.failureKind?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() },
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

    private fun attachPersistedProblems(
        progress: BuildProgressSnapshot?,
        mcpResult: McpBuildResult?,
        status: String,
        isRunning: Boolean,
    ): BuildProgressSnapshot? {
        val persistedProblems = mcpResult?.problems.orEmpty()
        if (persistedProblems.isEmpty()) {
            return progress
        }
        if (progress != null) {
            val mergedProblems = progress.problems.toMutableList()
            ProblemsSerializer.mergeDistinct(mergedProblems, persistedProblems)
            return if (mergedProblems == progress.problems) {
                progress
            } else {
                progress.copy(problems = mergedProblems)
            }
        }
        if (isRunning) {
            return null
        }
        return mcpResult?.let { mcp ->
            BuildProgressSnapshot(
                status = status,
                currentOperation = null,
                completedTaskCount = 0,
                runningTaskCount = 0,
                failedTaskCount = mcp.failedTaskCount,
                failedGradleTaskCount = mcp.failedGradleTaskCount,
                failedTestCount = mcp.failedTestCount,
                completedTasks = emptyList(),
                runningTasks = emptyList(),
                failedTasks = mcp.failedTasks,
                failedGradleTasks = mcp.failedGradleTasks,
                failedTestNames = mcp.failedTestNames,
                recentEvents = emptyList(),
                totalEventCount = 0,
                problems = persistedProblems,
                failedTests = mcp.testFailures,
            )
        }
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

    private fun mergeEventDerivedProgress(
        base: BuildProgressSnapshot?,
        artifacts: PersistedBuildArtifacts,
        events: List<DiskBuildEvent>,
        status: String,
    ): BuildProgressSnapshot? {
        if (!DiskBuildProgress.hasActionableProgress(events)) {
            return base
        }
        val fromEvents = diskProgressFromEvents(artifacts, events, status)
        if (base == null) {
            return fromEvents
        }
        return base.copy(
            completedTaskCount = maxOf(base.completedTaskCount, fromEvents.completedTaskCount),
            runningTaskCount = fromEvents.runningTaskCount,
            failedTaskCount = maxOf(base.failedTaskCount, fromEvents.failedTaskCount),
            failedGradleTaskCount = maxOf(base.failedGradleTaskCount, fromEvents.failedGradleTaskCount),
            failedTestCount = maxOf(base.failedTestCount, fromEvents.failedTestCount),
            completedTasks = fromEvents.completedTasks.ifEmpty { base.completedTasks },
            runningTasks = fromEvents.runningTasks,
            failedTasks = if (base.failedTasks.isNotEmpty()) base.failedTasks else fromEvents.failedTasks,
            failedGradleTasks = if (base.failedGradleTasks.isNotEmpty()) {
                base.failedGradleTasks
            } else {
                fromEvents.failedGradleTasks
            },
            failedTestNames = if (base.failedTestNames.isNotEmpty()) {
                base.failedTestNames
            } else {
                fromEvents.failedTestNames
            },
            recentEvents = fromEvents.recentEvents,
            totalEventCount = maxOf(base.totalEventCount, fromEvents.totalEventCount),
            failedTests = fromEvents.failedTests,
        )
    }
}
