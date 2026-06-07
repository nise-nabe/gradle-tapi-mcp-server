package com.example.gradle.mcp.build

import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskStartEvent
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import java.time.Instant

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
        notifyAfter {
            synchronized(lock) {
                currentOperation = operation
                recordEventLocked("START", operation)
                true
            }
        }
    }

    fun markSucceeded() {
        notifyAfter {
            synchronized(lock) {
                if (status != STATUS_RUNNING) {
                    return@notifyAfter false
                }
                status = STATUS_SUCCEEDED
                currentOperation = null
                runningTasks.clear()
                recordEventLocked("FINISH", "Build succeeded")
                true
            }
        }
    }

    fun markFailed(message: String) {
        notifyAfter {
            synchronized(lock) {
                if (status != STATUS_RUNNING) {
                    return@notifyAfter false
                }
                status = STATUS_FAILED
                currentOperation = null
                runningTasks.clear()
                recordEventLocked("FAIL", message)
                true
            }
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
            notifyAfter {
                synchronized(lock) {
                    handleGradleEvent(event)
                    true
                }
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
                recordEventLocked("TASK_START", displayName)
            }
            is TaskFinishEvent -> {
                runningTasks.remove(displayName)
                when (val result = event.result) {
                    is org.gradle.tooling.events.task.TaskFailureResult -> {
                        failedTasks.add(displayName)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        recordEventLocked("TASK_FAIL", displayName, message)
                    }
                    is org.gradle.tooling.events.task.TaskSkippedResult -> {
                        completedTasks.add(displayName)
                        recordEventLocked("TASK_SKIP", displayName)
                    }
                    else -> {
                        completedTasks.add(displayName)
                        recordEventLocked("TASK_SUCCESS", displayName)
                    }
                }
            }
            is TestStartEvent -> {
                runningTasks.add(displayName)
                recordEventLocked("TEST_START", displayName)
            }
            is TestFinishEvent -> {
                runningTasks.remove(displayName)
                when (val result = event.result) {
                    is org.gradle.tooling.events.test.TestFailureResult -> {
                        failedTasks.add(displayName)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        recordEventLocked("TEST_FAIL", displayName, message)
                    }
                    is org.gradle.tooling.events.test.TestSkippedResult -> {
                        completedTasks.add(displayName)
                        recordEventLocked("TEST_SKIP", displayName)
                    }
                    else -> {
                        completedTasks.add(displayName)
                        recordEventLocked("TEST_SUCCESS", displayName)
                    }
                }
            }
            else -> recordEventLocked(event.javaClass.simpleName, displayName)
        }
    }

    private fun notifyAfter(block: () -> Boolean) {
        if (block()) {
            onUpdate?.invoke()
        }
    }

    private fun recordEventLocked(eventType: String, displayName: String, outcome: String? = null) {
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
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"

        private const val MAX_RECENT_EVENTS = 30
    }
}
