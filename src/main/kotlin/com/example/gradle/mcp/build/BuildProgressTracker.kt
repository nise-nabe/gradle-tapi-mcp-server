package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProblemsSerializer
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskStartEvent
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestProgressEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.events.test.source.ClassSource
import org.gradle.tooling.events.test.source.ClasspathResourceSource
import org.gradle.tooling.events.test.source.DirectorySource
import org.gradle.tooling.events.test.source.FileSource
import org.gradle.tooling.events.test.source.FilesystemSource
import org.gradle.tooling.events.test.source.MethodSource
import org.gradle.tooling.events.test.source.NoSource
import org.gradle.tooling.events.test.source.OtherSource
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
    private val failedTests = LinkedHashMap<String, FailedTestSnapshot>()
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
                failedTests = failedTests.values.toList(),
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
            OperationType.PROJECT_CONFIGURATION,
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
                applyTaskEvent(
                    ProgressEventTypes.TEST_START,
                    displayName,
                    testDetails = extractTestDetails(event),
                )
            }
            is TestFinishEvent -> {
                when (val result = event.result) {
                    is org.gradle.tooling.events.test.TestFailureResult -> {
                        collectProblemsFromFailureResult(result)
                        val message = result.failures.firstOrNull()?.message ?: "failed"
                        applyTaskEvent(
                            ProgressEventTypes.TEST_FAIL,
                            displayName,
                            message,
                            extractTestDetails(event, message),
                        )
                    }
                    is org.gradle.tooling.events.test.TestSkippedResult -> {
                        applyTaskEvent(
                            ProgressEventTypes.TEST_SKIP,
                            displayName,
                            testDetails = extractTestDetails(event),
                        )
                    }
                    else -> {
                        applyTaskEvent(
                            ProgressEventTypes.TEST_SUCCESS,
                            displayName,
                            testDetails = extractTestDetails(event),
                        )
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

    private fun applyTaskEvent(
        eventType: String,
        displayName: String,
        outcome: String? = null,
        testDetails: TestProgressDetailsSnapshot? = null,
    ) {
        taskProgress.apply(eventType, displayName)
        rememberFailedTest(eventType, displayName, outcome, testDetails)
        recordEventLocked(eventType, displayName, outcome, testDetails)
    }

    private fun notifyAfter(block: () -> Boolean) {
        if (block()) {
            onUpdate?.invoke()
        }
    }

    private fun recordEventLocked(
        eventType: String,
        displayName: String,
        outcome: String? = null,
        testDetails: TestProgressDetailsSnapshot? = null,
    ) {
        totalEventCount += 1
        recentEvents.addLast(
            ProgressEventSnapshot(
                timestamp = Instant.now().toString(),
                eventType = eventType,
                displayName = displayName,
                outcome = outcome,
                testDetails = testDetails,
            ),
        )
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.removeFirst()
        }
    }

    private fun rememberFailedTest(
        eventType: String,
        displayName: String,
        outcome: String?,
        testDetails: TestProgressDetailsSnapshot?,
    ) {
        if (eventType != ProgressEventTypes.TEST_FAIL) {
            return
        }
        val failedTest = FailedTestSnapshot(
            className = testDetails?.className,
            methodName = testDetails?.methodName,
            displayName = displayName,
            failureMessage = testDetails?.failureMessage ?: outcome,
        )
        failedTests.remove(failedTest.stableKey())
        failedTests[failedTest.stableKey()] = failedTest
        while (failedTests.size > MAX_FAILED_TESTS) {
            failedTests.remove(failedTests.entries.first().key)
        }
    }

    private fun extractTestDetails(
        event: TestProgressEvent,
        failureMessage: String? = null,
    ): TestProgressDetailsSnapshot? {
        val descriptor = event.descriptor as? JvmTestOperationDescriptor
        val source = descriptor?.source
        var className = descriptor?.className
        var methodName = descriptor?.methodName
        val sourceType = when (source) {
            null -> null
            is MethodSource -> {
                className = className ?: source.className
                methodName = methodName ?: source.methodName
                "method"
            }
            is ClassSource -> {
                className = className ?: source.className
                "class"
            }
            is FileSource -> "file"
            is DirectorySource -> "directory"
            is ClasspathResourceSource -> "classpath_resource"
            is FilesystemSource -> "filesystem"
            is OtherSource -> "other"
            is NoSource -> "none"
            else -> source.javaClass.simpleName.ifBlank { source.javaClass.name }
        }
        val sourcePath = when (source) {
            is FileSource -> source.file.path
            is DirectorySource -> source.file.path
            is FilesystemSource -> source.file.path
            is ClasspathResourceSource -> source.classpathResourceName
            else -> null
        }
        val sourcePosition = when (source) {
            is FileSource -> source.position
            is ClasspathResourceSource -> source.position
            else -> null
        }
        if (
            className == null &&
            methodName == null &&
            sourceType == null &&
            sourcePath == null &&
            sourcePosition == null &&
            failureMessage == null
        ) {
            return null
        }
        return TestProgressDetailsSnapshot(
            className = className,
            methodName = methodName,
            sourceType = sourceType,
            sourcePath = sourcePath,
            sourceLine = sourcePosition?.line,
            sourceColumn = sourcePosition?.column,
            failureMessage = failureMessage,
        )
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val MAX_RECENT_EVENTS = 30
        private const val MAX_FAILED_TESTS = 10
    }
}
