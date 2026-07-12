package com.example.gradle.mcp.build

data class BuildStatusView(
    val buildId: String,
    val kind: String?,
    val status: String,
    val startedAt: String?,
    val finishedAt: String?,
    val tasks: List<String>,
    val selection: TestRunSelection? = null,
    val error: String?,
    val failureKind: FailureKind? = null,
    val outcome: String?,
    val buildSummary: Map<String, Any?>?,
    val progress: BuildProgressSnapshot?,
    val progressAvailable: Boolean,
    val stdout: CapturedStreamSnapshot,
    val stderr: CapturedStreamSnapshot,
    val statusSource: String,
    val recordDirectory: String? = null,
) {
    val testClasses: List<String> get() = selection.testClassesForReporting()
    val testMethods: Map<String, List<String>> get() = selection.testMethodsOrEmpty()
    val taskPath: String? get() = selection.taskPathOrNull()
    val includePatterns: List<String> get() = selection.includePatternsOrEmpty()

    companion object {
        const val SOURCE_MEMORY = "memory"
        const val SOURCE_DISK = "disk"

        fun fromRecord(record: BuildRecord): BuildStatusView {
            val progress = record.progressTracker.snapshot()
            val stdout = record.streams.stdoutSnapshot()
            val stderr = record.streams.stderrSnapshot()
            val isTerminal = progress.status != BuildProgressTracker.STATUS_RUNNING
            return BuildStatusView(
                buildId = record.id,
                kind = record.kind.name.lowercase(),
                status = progress.status,
                startedAt = record.startedAt.toString(),
                finishedAt = record.finishedAt?.toString(),
                tasks = record.tasks,
                selection = record.selection,
                error = record.errorMessage,
                failureKind = record.failureKind,
                outcome = if (isTerminal) {
                    BuildOutputParser.outcomeFromStatus(progress.status)
                } else {
                    null
                },
                buildSummary = if (isTerminal) {
                    BuildOutputParser.summaryFromStdout(stdout.text)
                } else {
                    null
                },
                progress = progress,
                progressAvailable = true,
                stdout = stdout,
                stderr = stderr,
                statusSource = SOURCE_MEMORY,
            )
        }
    }
}
