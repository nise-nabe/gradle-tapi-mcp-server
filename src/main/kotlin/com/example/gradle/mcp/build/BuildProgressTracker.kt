package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProblemsSerializer
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.download.FileDownloadFinishEvent
import org.gradle.tooling.events.download.FileDownloadNotFoundResult
import org.gradle.tooling.events.download.FileDownloadResult
import org.gradle.tooling.events.download.FileDownloadStartEvent
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskStartEvent
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import java.time.Instant

class BuildProgressTracker(
    private val trackDownloads: Boolean = false,
    private val onUpdate: (() -> Unit)? = null,
) {
    private val lock = Any()
    private val taskProgress = ProgressEventAccumulator()

    private var status: String = STATUS_RUNNING
    private var currentOperation: String? = null
    private val recentEvents = ArrayDeque<ProgressEventSnapshot>()
    private val problems = mutableListOf<BuildProblemSnapshot>()
    private val activeDownloads = LinkedHashMap<String, DownloadProgressSnapshot>()
    private val recentDownloads = ArrayDeque<DownloadProgressSnapshot>()
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
                activeDownloads.clear()
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
                activeDownloads.clear()
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
                activeDownloads.clear()
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
                recentDownloads = buildRecentDownloadsLocked(),
                activeDownloadCount = activeDownloads.size,
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
        val operationTypes = buildList {
            add(OperationType.TASK)
            add(OperationType.TEST)
            add(OperationType.ROOT)
            add(OperationType.PROJECT_CONFIGURATION)
            if (trackDownloads) {
                add(OperationType.FILE_DOWNLOAD)
            }
        }
        launcher.addProgressListener(
            asGradleListener(),
            *operationTypes.toTypedArray(),
        )
    }

    private fun handleGradleEvent(event: ProgressEvent) {
        when (event) {
            is FileDownloadStartEvent -> {
                recordDownloadStart(event)
                return
            }
            is FileDownloadFinishEvent -> {
                recordDownloadFinish(event)
                return
            }
        }

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
            is ProjectConfigurationStartEvent -> {
                recordEventLocked(ProgressEventTypes.CONFIG_START, displayName)
            }
            is ProjectConfigurationFinishEvent -> {
                when (val result = event.result) {
                    is org.gradle.tooling.events.configuration.ProjectConfigurationFailureResult -> {
                        collectProblemsFromFailureResult(result)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        recordEventLocked(ProgressEventTypes.CONFIG_FAIL, displayName, message)
                    }
                    else -> {
                        recordEventLocked(ProgressEventTypes.CONFIG_FINISH, displayName)
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

    private fun recordDownloadStart(event: FileDownloadStartEvent) {
        val uri = event.descriptor.uri.toString()
        activeDownloads[uri] = DownloadProgressSnapshot(
            uri = uri,
            status = DOWNLOAD_STATUS_DOWNLOADING,
            displayName = event.displayName,
        )
        totalEventCount += 1
    }

    private fun recordDownloadFinish(event: FileDownloadFinishEvent) {
        val uri = event.descriptor.uri.toString()
        val result = event.result
        val bytesDownloaded = if (result is FileDownloadResult) result.bytesDownloaded else null
        val downloadStatus = when (result) {
            is FailureResult -> DOWNLOAD_STATUS_FAILED
            is FileDownloadNotFoundResult -> DOWNLOAD_STATUS_NOT_FOUND
            is SuccessResult -> DOWNLOAD_STATUS_SUCCEEDED
            else -> DOWNLOAD_STATUS_SUCCEEDED
        }
        activeDownloads.remove(uri)
        recentDownloads.addLast(
            DownloadProgressSnapshot(
                uri = uri,
                status = downloadStatus,
                displayName = event.displayName,
                bytesDownloaded = bytesDownloaded,
            ),
        )
        while (recentDownloads.size > MAX_RECENT_DOWNLOADS) {
            recentDownloads.removeFirst()
        }
        totalEventCount += 1
    }

    private fun buildRecentDownloadsLocked(): List<DownloadProgressSnapshot> {
        val combined = ArrayDeque<DownloadProgressSnapshot>(recentDownloads.size + activeDownloads.size)
        combined.addAll(recentDownloads)
        combined.addAll(activeDownloads.values)
        return combined.toList()
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

        const val DOWNLOAD_STATUS_DOWNLOADING = "downloading"
        const val DOWNLOAD_STATUS_SUCCEEDED = "succeeded"
        const val DOWNLOAD_STATUS_FAILED = "failed"
        const val DOWNLOAD_STATUS_NOT_FOUND = "not_found"

        private const val MAX_RECENT_EVENTS = 30
        private const val MAX_RECENT_DOWNLOADS = 30
    }
}
