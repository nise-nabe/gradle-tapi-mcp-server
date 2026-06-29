package com.example.gradle.mcp.build

internal object FailedTestSnapshots {
    fun mergeDistinct(vararg lists: List<FailedTestSnapshot>): List<FailedTestSnapshot> {
        val merged = LinkedHashMap<String, FailedTestSnapshot>()
        for (failedTest in lists.flatMap { it }) {
            merged[failedTest.stableKey()] = failedTest
        }
        return merged.values.toList()
    }

    fun fromEvents(events: Iterable<ProgressEventSnapshot>): List<FailedTestSnapshot> =
        mergeDistinct(events.mapNotNull { it.toFailedTestSnapshot() })

    fun remember(
        target: LinkedHashMap<String, FailedTestSnapshot>,
        failedTest: FailedTestSnapshot,
        maxSize: Int,
    ) {
        val key = failedTest.stableKey()
        target.remove(key)
        target[key] = failedTest
        while (target.size > maxSize) {
            target.remove(target.entries.first().key)
        }
    }
}
