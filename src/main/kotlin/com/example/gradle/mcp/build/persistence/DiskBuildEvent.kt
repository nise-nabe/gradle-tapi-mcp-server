package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.FailedTestSnapshot
import com.example.gradle.mcp.build.ProgressEventAccumulator
import com.example.gradle.mcp.build.ProgressEventSnapshot
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.build.TestProgressDetailsSnapshot

data class DiskBuildEvent(
    val timestamp: String,
    val eventType: String,
    val displayName: String,
    val outcome: String? = null,
    val testDetails: TestProgressDetailsSnapshot? = null,
) {
    fun toProgressEvent(): ProgressEventSnapshot =
        ProgressEventSnapshot(
            timestamp = timestamp,
            eventType = eventType,
            displayName = displayName,
            outcome = outcome,
            testDetails = testDetails,
        )
}

internal object DiskBuildProgress {
    fun snapshotFromEvents(
        events: List<DiskBuildEvent>,
        status: String,
        currentOperation: String?,
    ): BuildProgressSnapshot {
        val accumulator = ProgressEventAccumulator()
        for (event in events) {
            accumulator.apply(event.eventType, event.displayName)
        }
        val recentEvents = events.map { it.toProgressEvent() }
        val failedTests = recentEvents.failedTests()
        return accumulator.snapshot(
            status = status,
            currentOperation = currentOperation,
            recentEvents = recentEvents,
            totalEventCount = recentEvents.size,
            failedTests = failedTests,
        )
    }

    fun hasActionableProgress(events: List<DiskBuildEvent>): Boolean =
        events.any { event -> event.eventType !in ProgressEventTypes.NON_ACTIONABLE }

    private fun List<ProgressEventSnapshot>.failedTests(): List<FailedTestSnapshot> {
        val distinct = LinkedHashMap<String, FailedTestSnapshot>()
        for (event in this) {
            val failedTest = event.toFailedTestSnapshot() ?: continue
            distinct[failedTest.stableKey()] = failedTest
        }
        return distinct.values.toList()
    }
}
