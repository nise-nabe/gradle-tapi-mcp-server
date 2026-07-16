package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpBuildNotifier
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel

internal class BuildProgressNotifier(
    private val delegate: McpBuildNotifier?,
) {
    fun notifyIfNeeded(tracker: BuildProgressTracker) {
        val active = delegate ?: return
        if (!tracker.shouldNotifyProgress()) {
            return
        }
        sendProgress(active, tracker, final = false)
        sendLog(active, tracker)
    }

    fun notifyFinal(tracker: BuildProgressTracker) {
        val active = delegate ?: return
        sendProgress(active, tracker, final = true)
        sendLog(active, tracker)
    }

    private fun sendProgress(
        delegate: McpBuildNotifier,
        tracker: BuildProgressTracker,
        final: Boolean,
    ) {
        val snapshot = tracker.snapshot()
        val completed = snapshot.completedTaskCount
        val total = (completed + snapshot.runningTaskCount + snapshot.failedTaskCount).coerceAtLeast(1)
        val message = buildString {
            append(snapshot.currentOperation ?: "Gradle build")
            append(" — completed ")
            append(completed)
            if (snapshot.failedTaskCount > 0) {
                append(", failed ")
                append(snapshot.failedTaskCount)
            }
        }
        delegate.notifyProgress(
            progress = if (final) total.toDouble() else completed.toDouble(),
            total = total.toDouble(),
            message = message,
        )
    }

    private fun sendLog(delegate: McpBuildNotifier, tracker: BuildProgressTracker) {
        val snapshot = tracker.snapshot()
        val latest = snapshot.recentEvents.lastOrNull() ?: return
        val level = when (latest.eventType) {
            "TASK_FAIL", "TEST_FAIL", "CONFIG_FAIL", "FAIL" -> LoggingLevel.Error
            "TASK_SKIP", "TEST_SKIP" -> LoggingLevel.Warning
            else -> LoggingLevel.Info
        }
        delegate.notifyLog(
            message = "${latest.eventType}: ${latest.displayName}",
            level = level,
        )
    }
}
