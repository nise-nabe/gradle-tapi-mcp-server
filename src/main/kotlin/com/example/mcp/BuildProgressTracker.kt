package com.example.mcp

import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskStartEvent
import java.time.Instant

data class ProgressEventSnapshot(
    val timestamp: String,
    val eventType: String,
    val displayName: String,
    val outcome: String? = null,
)

data class BuildProgressSnapshot(
    val status: String,
    val currentOperation: String?,
    val completedTaskCount: Int,
    val runningTaskCount: Int,
    val failedTaskCount: Int,
    val completedTasks: List<String>,
    val runningTasks: List<String>,
    val failedTasks: List<String>,
    val recentEvents: List<ProgressEventSnapshot>,
    val totalEventCount: Int,
)

class BuildProgressTracker(
    private val onUpdate: (() -> Unit)? = null,
) {
    private val lock = Any()

    private var status: String = STATUS_RUNNING
    private var currentOperation: String? = null
    private val completedTasks = LinkedHashSet<String>()
    private val runningTasks = LinkedHashSet<String>()
    private val failedTasks = LinkedHashSet<String>()
    private val recentEvents = ArrayDeque<ProgressEventSnapshot>()
    private var totalEventCount = 0
    private var lastNotifiedEventCount = 0

    fun markStarting(operation: String) {
        synchronized(lock) {
            currentOperation = operation
            recordEvent("START", operation)
        }
    }

    fun markSucceeded() {
        synchronized(lock) {
            status = STATUS_SUCCEEDED
            currentOperation = null
            runningTasks.clear()
            recordEvent("FINISH", "Build succeeded")
        }
    }

    fun markFailed(message: String) {
        synchronized(lock) {
            status = STATUS_FAILED
            currentOperation = null
            runningTasks.clear()
            recordEvent("FAIL", message)
        }
    }

    fun snapshot(): BuildProgressSnapshot =
        synchronized(lock) {
            BuildProgressSnapshot(
                status = status,
                currentOperation = currentOperation,
                completedTaskCount = completedTasks.size,
                runningTaskCount = runningTasks.size,
                failedTaskCount = failedTasks.size,
                completedTasks = completedTasks.toList(),
                runningTasks = runningTasks.toList(),
                failedTasks = failedTasks.toList(),
                recentEvents = recentEvents.toList(),
                totalEventCount = totalEventCount,
            )
        }

    fun shouldNotifyProgress(): Boolean =
        synchronized(lock) {
            if (totalEventCount == lastNotifiedEventCount) {
                return false
            }
            lastNotifiedEventCount = totalEventCount
            true
        }

    fun asGradleListener(): ProgressListener =
        ProgressListener { event ->
            synchronized(lock) {
                handleGradleEvent(event)
            }
        }

    fun configureLauncher(launcher: org.gradle.tooling.ConfigurableLauncher<*>) {
        launcher.addProgressListener(
            asGradleListener(),
            OperationType.TASK,
            OperationType.TEST,
        )
    }

    private fun handleGradleEvent(event: ProgressEvent) {
        val displayName = event.displayName
        currentOperation = displayName

        when (event) {
            is TaskStartEvent -> {
                runningTasks.add(displayName)
                recordEvent("TASK_START", displayName)
            }
            is TaskFinishEvent -> {
                runningTasks.remove(displayName)
                when (val result = event.result) {
                    is org.gradle.tooling.events.task.TaskFailureResult -> {
                        failedTasks.add(displayName)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        recordEvent("TASK_FAIL", displayName, message)
                    }
                    is org.gradle.tooling.events.task.TaskSkippedResult -> {
                        recordEvent("TASK_SKIP", displayName)
                    }
                    else -> {
                        completedTasks.add(displayName)
                        recordEvent("TASK_SUCCESS", displayName)
                    }
                }
            }
            else -> recordEvent(event.javaClass.simpleName, displayName)
        }
    }

    private fun recordEvent(eventType: String, displayName: String, outcome: String? = null) {
        totalEventCount += 1
        recentEvents.addLast(
            ProgressEventSnapshot(
                timestamp = Instant.now().toString(),
                eventType = eventType,
                displayName = displayName,
                outcome = outcome,
            ),
        )
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.removeFirst()
        }
        onUpdate?.invoke()
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"

        private const val MAX_RECENT_EVENTS = 30
    }
}
