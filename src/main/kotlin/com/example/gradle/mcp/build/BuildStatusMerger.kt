package com.example.gradle.mcp.build

/**
 * Merges in-memory and disk [BuildStatusView]s. Gradle on-disk records win when status
 * disagrees (for example MCP marked a build failed on disconnect while Gradle kept running).
 */
internal object BuildStatusMerger {
    fun merge(memory: BuildStatusView, disk: BuildStatusView): BuildStatusView {
        if (memory.status == BuildProgressTracker.STATUS_RUNNING &&
            disk.status == BuildProgressTracker.STATUS_RUNNING
        ) {
            return memory.copy(
                recordDirectory = disk.recordDirectory,
                stdout = pickStream(disk.stdout, memory.stdout),
                stderr = pickStream(disk.stderr, memory.stderr),
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
            disk.failedTaskCount > memory.failedTaskCount -> disk
            memory.failedTaskCount > disk.failedTaskCount -> memory
            disk.problems.size > memory.problems.size -> disk
            memory.problems.size > disk.problems.size -> memory
            disk.totalEventCount > memory.totalEventCount -> disk
            memory.totalEventCount > disk.totalEventCount -> memory
            else -> memory
        }
}
