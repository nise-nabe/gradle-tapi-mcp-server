package com.example.gradle.mcp.build

/**
 * TestLauncher counts every failed attempt and, after the build, throws
 * "Test failed." even when a later retry passed and the test task itself succeeded.
 * That exception is not Gradle's build outcome.
 */
internal object TestRetryOutcomes {
    fun failureIsPassedOnRetry(exception: Throwable, progress: BuildProgressSnapshot): Boolean {
        if (progress.failedGradleTaskCount > 0) {
            return false
        }
        if (progress.failedTests.any(FailedTestSnapshots::isReportableFailure)) {
            return false
        }
        if (progress.failedTestCount > 0) {
            return false
        }
        if (progress.flakyTests.isEmpty()) {
            return false
        }
        return causeMessages(exception).any { message ->
            message.trimStart().startsWith("Test failed.")
        }
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
