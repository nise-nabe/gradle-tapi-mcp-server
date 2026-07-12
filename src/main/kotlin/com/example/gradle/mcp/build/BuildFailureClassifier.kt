package com.example.gradle.mcp.build

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
        return ClassifiedFailure(FailureKind.TASK_FAILURE, error)
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

    private fun isLikelyConnectionFailure(
        error: String?,
        progress: BuildProgressSnapshot?,
        stdout: String,
    ): Boolean {
        if (error.isNullOrBlank()) {
            return false
        }
        val lower = error.lowercase()
        val looksLikeConnection = lower.contains("gradle distribution") ||
            (lower.contains("connection") && lower.contains("could not execute"))
        if (!looksLikeConnection) {
            return false
        }
        if (stdout.contains("BUILD FAILED") || stdout.contains("BUILD SUCCESSFUL")) {
            return false
        }
        if ((progress?.completedTaskCount ?: 0) > 0) {
            return false
        }
        if (hasEvidenceOfTestFailures(progress, null, stdout)) {
            return false
        }
        return true
    }
}
