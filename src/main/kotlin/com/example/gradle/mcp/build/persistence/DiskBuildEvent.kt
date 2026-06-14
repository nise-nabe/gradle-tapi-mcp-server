package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.ProgressEventAccumulator
import com.example.gradle.mcp.build.ProgressEventSnapshot
import com.example.gradle.mcp.build.ProgressEventTypes

data class DiskBuildEvent(
    val timestamp: String,
    val eventType: String,
    val displayName: String,
    val outcome: String? = null,
) {
    fun toProgressEvent(): ProgressEventSnapshot =
        ProgressEventSnapshot(
            timestamp = timestamp,
            eventType = eventType,
            displayName = displayName,
            outcome = outcome,
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
        return accumulator.snapshot(
            status = status,
            currentOperation = currentOperation,
            recentEvents = recentEvents,
            totalEventCount = recentEvents.size,
        )
    }

    fun hasActionableProgress(events: List<DiskBuildEvent>): Boolean =
        events.any { event -> event.eventType !in ProgressEventTypes.NON_ACTIONABLE }
}
