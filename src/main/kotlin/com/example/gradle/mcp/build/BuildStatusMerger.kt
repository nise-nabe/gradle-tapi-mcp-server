package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProblemsSerializer
import com.example.gradle.mcp.protocol.ProgressResponseOptions

/**
 * Merges in-memory and disk [BuildStatusView]s for status polling.
 * While memory reports `running`, memory status is authoritative and disk contributes
 * streams, [BuildStatusView.recordDirectory], and task events from `events.ndjson`.
 * When memory is terminal or absent, Gradle on-disk records win when status disagrees
 * (for example MCP marked a build failed on disconnect while Gradle kept running).
 */
internal object BuildStatusMerger {
    fun merge(memory: BuildStatusView, disk: BuildStatusView): BuildStatusView {
        if (memory.status == BuildProgressTracker.STATUS_RUNNING) {
            val mergedProgress = mergeRunningProgress(memory.progress, disk.progress)
            return memory.copy(
                recordDirectory = disk.recordDirectory,
                stdout = pickStream(disk.stdout, memory.stdout),
                stderr = pickStream(disk.stderr, memory.stderr),
                progress = mergedProgress,
                progressAvailable = mergedProgress != null || memory.progressAvailable || disk.progressAvailable,
            )
        }
        if (disk.status != memory.status) {
            return preferDisk(disk, memory)
        }
        return preferDisk(disk, memory).copy(statusSource = memory.statusSource)
    }

    private fun preferDisk(disk: BuildStatusView, memory: BuildStatusView): BuildStatusView {
        val stdout = pickStream(disk.stdout, memory.stdout)
        val stderr = pickStream(disk.stderr, memory.stderr)
        val progress = mergedProgress(disk, memory)
        return disk.copy(
            stdout = stdout,
            stderr = stderr,
            buildSummary = mergedTerminalBuildSummary(disk.status, stdout, disk.buildSummary),
            progress = progress,
            progressAvailable = progress != null,
        )
    }

    private fun mergedProgress(disk: BuildStatusView, memory: BuildStatusView): BuildProgressSnapshot? =
        if (disk.status == memory.status) {
            pickProgress(disk.progress, memory.progress)
        } else {
            disk.progress
        }

    private fun mergeRunningProgress(
        memory: BuildProgressSnapshot?,
        disk: BuildProgressSnapshot?,
    ): BuildProgressSnapshot? =
        when {
            memory == null && disk == null -> null
            memory == null -> disk?.asRunningProgress()
            disk == null -> memory
            else -> {
                val mergedRecentEvents = mergeRecentEvents(memory.recentEvents, disk.recentEvents)
                memory.copy(
                    status = BuildProgressTracker.STATUS_RUNNING,
                    currentOperation = disk.currentOperation
                        ?: memory.currentOperation
                        ?: mergedRecentEvents.lastOrNull()?.displayName,
                    completedTaskCount = maxOf(memory.completedTaskCount, disk.completedTaskCount),
                    runningTaskCount = maxOf(memory.runningTaskCount, disk.runningTaskCount),
                    failedTaskCount = memory.failedTaskCount,
                    failedGradleTaskCount = memory.failedGradleTaskCount,
                    failedTestCount = memory.failedTestCount,
                    completedTasks = pickRicherTaskList(memory.completedTasks, disk.completedTasks),
                    runningTasks = pickRicherTaskList(memory.runningTasks, disk.runningTasks),
                    failedTasks = memory.failedTasks,
                    failedGradleTasks = memory.failedGradleTasks,
                    failedTestNames = memory.failedTestNames,
                    recentEvents = mergedRecentEvents,
                    totalEventCount = maxOf(
                        memory.totalEventCount,
                        disk.totalEventCount,
                        mergedRecentEvents.size,
                    ),
                    problems = memory.problems,
                    failedTests = memory.failedTests,
                )
            }
        }

    private fun BuildProgressSnapshot.asRunningProgress(): BuildProgressSnapshot =
        copy(
            status = BuildProgressTracker.STATUS_RUNNING,
            failedTaskCount = 0,
            failedGradleTaskCount = 0,
            failedTestCount = 0,
            failedTasks = emptyList(),
            failedGradleTasks = emptyList(),
            failedTestNames = emptyList(),
            problems = emptyList(),
            failedTests = emptyList(),
        )

    private fun mergeRecentEvents(
        memory: List<ProgressEventSnapshot>,
        disk: List<ProgressEventSnapshot>,
    ): List<ProgressEventSnapshot> =
        (memory + disk)
            .distinctBy { "${it.timestamp}|${it.eventType}|${it.displayName}" }
            .sortedBy { it.timestamp }
            .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)

    private fun pickRicherTaskList(memory: List<String>, disk: List<String>): List<String> =
        if (disk.size > memory.size) disk else memory

    private fun mergedTerminalBuildSummary(
        status: String,
        stdout: CapturedStreamSnapshot,
        diskSummary: Map<String, Any?>?,
    ): Map<String, Any?>? {
        if (status == BuildProgressTracker.STATUS_RUNNING) {
            return null
        }
        if (stdout.text.isNotBlank()) {
            val summary = BuildOutputParser.parse(stdout.text)
            if (summary.resultLine != null ||
                summary.taskSummaryLine != null ||
                summary.failureSummary.isNotEmpty()
            ) {
                return BuildOutputParser.toResponseMap(summary)
            }
        }
        return diskSummary
    }

    private fun pickStream(
        disk: CapturedStreamSnapshot,
        memory: CapturedStreamSnapshot,
    ): CapturedStreamSnapshot =
        when {
            disk.totalChars > memory.totalChars -> disk
            memory.totalChars > disk.totalChars -> memory
            else -> disk
        }

    private fun pickProgress(
        disk: BuildProgressSnapshot?,
        memory: BuildProgressSnapshot?,
    ): BuildProgressSnapshot? =
        when {
            disk == null -> memory
            memory == null -> disk
            else -> {
                val (base, other) = when {
                    disk.failedTaskCount > memory.failedTaskCount -> disk to memory
                    memory.failedTaskCount > disk.failedTaskCount -> memory to disk
                    disk.totalEventCount > memory.totalEventCount -> disk to memory
                    memory.totalEventCount > disk.totalEventCount -> memory to disk
                    else -> memory to disk
                }
                val mergedProblems = base.problems.toMutableList()
                ProblemsSerializer.mergeDistinct(mergedProblems, other.problems)
                val mergedFailedTests = FailedTestSnapshots.mergeDistinct(base.failedTests, other.failedTests)
                val mergedFailedTestNames = FailedTestSnapshots.methodLevelNames(mergedFailedTests)
                if (
                    mergedProblems == base.problems &&
                    mergedFailedTests == base.failedTests &&
                    mergedFailedTestNames == base.failedTestNames
                ) {
                    base
                } else {
                    base.copy(
                        problems = mergedProblems,
                        failedTests = mergedFailedTests,
                        failedTestCount = FailedTestSnapshots.methodLevelCount(mergedFailedTests),
                        failedTestNames = mergedFailedTestNames,
                    )
                }
            }
        }
}
