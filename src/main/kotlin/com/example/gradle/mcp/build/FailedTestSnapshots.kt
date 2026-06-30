package com.example.gradle.mcp.build

internal object FailedTestSnapshots {
    const val MAX_TRACKED_FAILED_TESTS = 10

    fun mergeDistinct(vararg lists: List<FailedTestSnapshot>): List<FailedTestSnapshot> {
        val merged = LinkedHashMap<String, FailedTestSnapshot>()
        for (list in lists) {
            for (failedTest in list) {
                putLatest(merged, failedTest)
            }
        }
        return trimToMax(merged, MAX_TRACKED_FAILED_TESTS)
    }

    fun fromEvents(events: Iterable<ProgressEventSnapshot>): List<FailedTestSnapshot> {
        val merged = LinkedHashMap<String, FailedTestSnapshot>()
        for (event in events) {
            event.toFailedTestSnapshot()?.let { putLatest(merged, it) }
        }
        return trimToMax(merged, MAX_TRACKED_FAILED_TESTS)
    }

    fun fromTestFailure(
        displayName: String,
        outcome: String?,
        testDetails: TestProgressDetailsSnapshot?,
    ): FailedTestSnapshot =
        FailedTestSnapshot(
            className = testDetails?.className,
            methodName = testDetails?.methodName,
            displayName = displayName,
            failureMessage = testDetails?.failureMessage ?: outcome,
        )

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
        val key = failedTest.stableKey()
        target.remove(key)
        target[key] = failedTest
    }
}
