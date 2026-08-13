package com.example.gradle.mcp.build

import org.gradle.tooling.GradleConnectionException

data class ClassifiedFailure(
    val failureKind: FailureKind?,
    val error: String?,
)

object BuildFailureClassifier {
    private val testsCompletedWithFailuresRegex =
        Regex("""\d+ tests? completed.*\d+ failed""", RegexOption.IGNORE_CASE)
    private val testFailedLineRegex =
        Regex("""\S+ > \S+ FAILED""")

    fun classify(
        status: String,
        kind: String?,
        error: String?,
        progress: BuildProgressSnapshot?,
        stdout: String,
    ): ClassifiedFailure {
        if (status == BuildProgressTracker.STATUS_CANCELLED) {
            return ClassifiedFailure(FailureKind.CANCELLED, error)
        }
        if (status != BuildProgressTracker.STATUS_FAILED) {
            return ClassifiedFailure(null, error)
        }
        if (hasEvidenceOfTestFailures(progress, kind, stdout)) {
            return ClassifiedFailure(FailureKind.TEST_FAILURE, null)
        }
        if (isLikelyConnectionFailure(error, progress, stdout)) {
            return ClassifiedFailure(FailureKind.CONNECTION_FAILURE, error)
        }
        return ClassifiedFailure(FailureKind.TASK_FAILURE, unwrapTaskFailureError(error, progress, stdout))
    }

    fun unwrapBuildFailureMessage(exception: Throwable): String {
        val fromDetailedFailures = detailedFailureMessages(exception)
            .firstOrNull { !isToolingConnectionWrapper(it) }
        if (fromDetailedFailures != null) {
            return fromDetailedFailures
        }
        val fromCauses = causeMessages(exception)
            .firstOrNull(::looksLikeTaskExecutionFailure)
        if (fromCauses != null) {
            return fromCauses
        }
        return exception.message?.takeIf { it.isNotBlank() } ?: exception.toString()
    }

    fun hasEvidenceOfTestFailures(
        progress: BuildProgressSnapshot?,
        kind: String?,
        stdout: String,
    ): Boolean {
        if (progress?.failedTests?.isNotEmpty() == true) {
            return true
        }
        if (kind == "tests" && progress != null && progress.failedTaskCount > 0 && stdout.isNotBlank()) {
            if (testsCompletedWithFailuresRegex.containsMatchIn(stdout)) {
                return true
            }
            if (testFailedLineRegex.containsMatchIn(stdout)) {
                return true
            }
        }
        if (stdout.isNotBlank()) {
            if (testsCompletedWithFailuresRegex.containsMatchIn(stdout)) {
                return true
            }
            if (testFailedLineRegex.containsMatchIn(stdout)) {
                return true
            }
        }
        return false
    }

    internal fun isToolingConnectionWrapper(message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }
        val lower = message.lowercase()
        return lower.contains("gradle distribution") ||
            (lower.contains("connection") && lower.contains("could not execute"))
    }

    private fun unwrapTaskFailureError(
        error: String?,
        progress: BuildProgressSnapshot?,
        stdout: String,
    ): String? {
        if (!isToolingConnectionWrapper(error)) {
            return error
        }
        val failedTask = progress?.failedGradleTasks?.firstOrNull()
            ?: progress?.failedTasks?.firstOrNull()
        if (!failedTask.isNullOrBlank()) {
            return "Execution failed for task '$failedTask'."
        }
        val fromStdout = BuildOutputParser.parse(stdout).failureSummary.firstOrNull()
        if (!fromStdout.isNullOrBlank()) {
            return "Execution failed for task '$fromStdout'."
        }
        return error
    }

    private fun isLikelyConnectionFailure(
        error: String?,
        progress: BuildProgressSnapshot?,
        stdout: String,
    ): Boolean {
        if (!isToolingConnectionWrapper(error)) {
            return false
        }
        if (stdout.contains("BUILD FAILED") || stdout.contains("BUILD SUCCESSFUL")) {
            return false
        }
        if ((progress?.completedTaskCount ?: 0) > 0) {
            return false
        }
        if ((progress?.failedTaskCount ?: 0) > 0) {
            return false
        }
        if (!progress?.failedTasks.isNullOrEmpty()) {
            return false
        }
        if (hasEvidenceOfTestFailures(progress, null, stdout)) {
            return false
        }
        return true
    }

    private fun looksLikeTaskExecutionFailure(message: String): Boolean =
        message.contains("Execution failed for task", ignoreCase = true)

    private fun detailedFailureMessages(exception: Throwable): List<String> {
        val connection = exception as? GradleConnectionException ?: return emptyList()
        return runCatching { connection.failures }
            .getOrNull()
            .orEmpty()
            .mapNotNull { failure -> failure.message?.takeIf { it.isNotBlank() } }
    }

    private fun causeMessages(exception: Throwable): Sequence<String> = sequence {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = exception
        while (current != null && seen.add(current)) {
            current.message?.takeIf { it.isNotBlank() }?.let { yield(it) }
            current = current.cause
        }
    }
}
