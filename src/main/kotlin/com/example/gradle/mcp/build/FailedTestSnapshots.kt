package com.example.gradle.mcp.build

internal object FailedTestSnapshots {
    const val MAX_TRACKED_FAILED_TESTS = 10

    /** Gradle's fallback when a composite test finish has no [org.gradle.tooling.Failure]. */
    const val EMPTY_FAILURE_MESSAGE = "failed"

    fun reportableFailures(tests: List<FailedTestSnapshot>): List<FailedTestSnapshot> =
        tests.filter(::isReportableFailure)

    fun qualifiedNames(tests: List<FailedTestSnapshot>): List<String> =
        tests.mapNotNull { it.qualifiedName() }

    fun testFailureMaps(tests: List<FailedTestSnapshot>): List<Map<String, Any?>> =
        reportableFailures(tests).map { it.toTestFailureMap() }

    fun mergeDistinct(vararg lists: List<FailedTestSnapshot>): List<FailedTestSnapshot> {
        val merged = LinkedHashMap<String, FailedTestSnapshot>()
        for (list in lists) {
            for (failedTest in list) {
                if (isReportableFailure(failedTest)) {
                    putLatest(merged, failedTest)
                }
            }
        }
        return trimToMax(merged, MAX_TRACKED_FAILED_TESTS)
    }

    /**
     * Final outcomes across memory and disk. A flaky entry wins over a failure for the
     * same test: one side has already seen the successful retry.
     */
    fun mergeOutcomes(
        failedLists: List<List<FailedTestSnapshot>>,
        flakyLists: List<List<FailedTestSnapshot>>,
    ): FoldedTestOutcomes {
        val failed = LinkedHashMap<String, FailedTestSnapshot>()
        val flaky = LinkedHashMap<String, FailedTestSnapshot>()
        for (list in failedLists) {
            for (failedTest in list) {
                recordFailure(failed, flaky, failedTest)
            }
        }
        for (list in flakyLists) {
            for (flakyTest in list) {
                rememberFlaky(failed, flaky, flakyTest)
            }
        }
        return FoldedTestOutcomes(
            failedTests = trimToMax(failed, MAX_TRACKED_FAILED_TESTS),
            flakyTests = trimToMax(flaky, MAX_TRACKED_FAILED_TESTS),
        )
    }

    fun fromEvents(events: Iterable<ProgressEventSnapshot>): List<FailedTestSnapshot> =
        foldOutcomes(events).failedTests

    fun foldOutcomes(events: Iterable<ProgressEventSnapshot>): FoldedTestOutcomes {
        val failed = LinkedHashMap<String, FailedTestSnapshot>()
        val flaky = LinkedHashMap<String, FailedTestSnapshot>()
        for (event in events) {
            when (event.eventType) {
                ProgressEventTypes.TEST_FAIL -> {
                    event.toFailedTestSnapshot()?.let { recordFailure(failed, flaky, it) }
                }
                ProgressEventTypes.TEST_SUCCESS -> {
                    recordSuccess(
                        failed,
                        flaky,
                        event.testDetails?.className,
                        event.testDetails?.methodName,
                        event.displayName,
                    )
                }
            }
        }
        return FoldedTestOutcomes(
            failedTests = trimToMax(failed, MAX_TRACKED_FAILED_TESTS),
            flakyTests = trimToMax(flaky, MAX_TRACKED_FAILED_TESTS),
        )
    }

    fun fromTestFailure(
        displayName: String,
        outcome: String?,
        testDetails: TestProgressDetailsSnapshot?,
        exceptionType: String? = null,
        failureType: String? = null,
    ): FailedTestSnapshot =
        FailedTestSnapshot(
            className = testDetails?.className,
            methodName = testDetails?.methodName,
            displayName = displayName,
            failureMessage = testDetails?.failureMessage ?: outcome,
            exceptionType = exceptionType ?: testDetails?.exceptionType,
            failureType = failureType ?: testDetails?.failureType,
            sourceFile = testDetails?.sourcePath?.substringAfterLast('/'),
            line = testDetails?.sourceLine,
        )

    fun isReportableFailure(failedTest: FailedTestSnapshot?): Boolean {
        failedTest ?: return false
        if (!failedTest.methodName.isNullOrBlank()) {
            return true
        }
        if (!failedTest.exceptionType.isNullOrBlank() || !failedTest.failureType.isNullOrBlank()) {
            return true
        }
        val message = failedTest.failureMessage?.trim().orEmpty()
        return message.isNotEmpty() && message != EMPTY_FAILURE_MESSAGE
    }

    fun recordFailure(
        failed: LinkedHashMap<String, FailedTestSnapshot>,
        flaky: LinkedHashMap<String, FailedTestSnapshot>,
        failedTest: FailedTestSnapshot,
    ) {
        if (!isReportableFailure(failedTest)) {
            return
        }
        flaky.remove(failedTest.identityKey())
        remember(failed, failedTest)
    }

    fun recordSuccess(
        failed: LinkedHashMap<String, FailedTestSnapshot>,
        flaky: LinkedHashMap<String, FailedTestSnapshot>,
        className: String?,
        methodName: String?,
        displayName: String,
    ) {
        val previous = failed.remove(identityKey(className, methodName, displayName)) ?: return
        remember(flaky, previous)
    }

    fun rememberFlaky(
        failed: LinkedHashMap<String, FailedTestSnapshot>,
        flaky: LinkedHashMap<String, FailedTestSnapshot>,
        flakyTest: FailedTestSnapshot,
    ) {
        failed.remove(flakyTest.identityKey())
        remember(flaky, flakyTest)
    }

    fun remember(
        target: LinkedHashMap<String, FailedTestSnapshot>,
        failedTest: FailedTestSnapshot,
        maxSize: Int = MAX_TRACKED_FAILED_TESTS,
    ) {
        putLatest(target, failedTest)
        while (target.size > maxSize) {
            target.remove(target.entries.first().key)
        }
    }

    private fun trimToMax(
        target: LinkedHashMap<String, FailedTestSnapshot>,
        maxSize: Int,
    ): List<FailedTestSnapshot> {
        while (target.size > maxSize) {
            target.remove(target.entries.first().key)
        }
        return target.values.toList()
    }

    private fun putLatest(
        target: LinkedHashMap<String, FailedTestSnapshot>,
        failedTest: FailedTestSnapshot,
    ) {
        val key = failedTest.identityKey()
        target.remove(key)
        target[key] = failedTest
    }
}

internal data class FoldedTestOutcomes(
    val failedTests: List<FailedTestSnapshot>,
    val flakyTests: List<FailedTestSnapshot>,
)

/** Method retries share class and method; Gradle's finish display name changes with the outcome. */
internal fun identityKey(className: String?, methodName: String?, displayName: String): String =
    if (!className.isNullOrBlank() && !methodName.isNullOrBlank()) {
        "$className|$methodName"
    } else {
        displayName
    }

internal fun FailedTestSnapshot.identityKey(): String =
    identityKey(className, methodName, displayName)
