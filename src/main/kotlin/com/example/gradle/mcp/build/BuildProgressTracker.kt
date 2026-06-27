package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProblemsSerializer
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
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
    private val taskProgress = ProgressEventAccumulator()

    private var status: String = STATUS_RUNNING
    private var currentOperation: String? = null
    private val recentEvents = ArrayDeque<ProgressEventSnapshot>()
    private val problems = mutableListOf<BuildProblemSnapshot>()
    private var totalEventCount = 0
    private var lastNotifiedEventCount = 0

    fun markStarting(operation: String) {
        notifyAfter {
            synchronized(lock) {
                currentOperation = operation
                recordEventLocked(ProgressEventTypes.START, operation)
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
                taskProgress.clearRunning()
                recordEventLocked(ProgressEventTypes.FINISH, "Build succeeded")
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
                taskProgress.clearRunning()
                recordEventLocked(ProgressEventTypes.FAIL, message)
                true
            }
        }
    }

    fun markCancelled(message: String) {
        notifyAfter {
            synchronized(lock) {
                if (status != STATUS_RUNNING) {
                    return@notifyAfter false
                }
                status = STATUS_CANCELLED
                currentOperation = null
                taskProgress.clearRunning()
                recordEventLocked(ProgressEventTypes.CANCEL, message)
                true
            }
        }
    }

    fun snapshot(): BuildProgressSnapshot =
        synchronized(lock) {
            taskProgress.snapshot(
                status = status,
                currentOperation = currentOperation,
                recentEvents = recentEvents.toList(),
                totalEventCount = totalEventCount,
                problems = problems.toList(),
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
            OperationType.ROOT,
        )
    }

    private fun handleGradleEvent(event: ProgressEvent) {
        val displayName = event.displayName
        currentOperation = displayName

        when (event) {
            is TaskStartEvent -> {
                applyTaskEvent(ProgressEventTypes.TASK_START, displayName)
            }
            is TaskFinishEvent -> {
                when (val result = event.result) {
                    is org.gradle.tooling.events.task.TaskFailureResult -> {
                        collectProblemsFromFailureResult(result)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        applyTaskEvent(ProgressEventTypes.TASK_FAIL, displayName, message)
                    }
                    is org.gradle.tooling.events.task.TaskSkippedResult -> {
                        applyTaskEvent(ProgressEventTypes.TASK_SKIP, displayName)
                    }
                    else -> {
                        applyTaskEvent(ProgressEventTypes.TASK_SUCCESS, displayName)
                    }
                }
            }
            is TestStartEvent -> {
                applyTaskEvent(ProgressEventTypes.TEST_START, displayName)
            }
            is TestFinishEvent -> {
                when (val result = event.result) {
                    is org.gradle.tooling.events.test.TestFailureResult -> {
                        collectProblemsFromFailureResult(result)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        applyTaskEvent(ProgressEventTypes.TEST_FAIL, displayName, message)
                    }
                    is org.gradle.tooling.events.test.TestSkippedResult -> {
                        applyTaskEvent(ProgressEventTypes.TEST_SKIP, displayName)
                    }
                    else -> {
                        applyTaskEvent(ProgressEventTypes.TEST_SUCCESS, displayName)
                    }
                }
            }
            is FinishEvent -> {
                when (val result = event.result) {
                    is FailureResult -> collectProblemsFromFailureResult(result)
                }
                recordEventLocked(ProgressEventTypes.ROOT_FINISH, displayName)
            }
            else -> recordEventLocked(event.javaClass.simpleName, displayName)
        }
    }

    private fun collectProblemsFromFailureResult(result: FailureResult) {
        val extracted = ProblemsSerializer.fromFailureResult(result)
        if (extracted.isEmpty()) {
            return
        }
        ProblemsSerializer.mergeDistinct(problems, extracted)
    }

    private fun applyTaskEvent(eventType: String, displayName: String, outcome: String? = null) {
        taskProgress.apply(eventType, displayName)
        recordEventLocked(eventType, displayName, outcome)
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
        const val STATUS_CANCELLED = "cancelled"

        private const val MAX_RECENT_EVENTS = 30
    }
}
