package com.example.mcp

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.ProjectConnection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class BuildRunRequest(
    val kind: BuildKind,
    val tasks: List<String> = emptyList(),
    val testClasses: List<String> = emptyList(),
    val arguments: List<String> = emptyList(),
    val jvmArguments: List<String> = emptyList(),
    val outputLimit: OutputLimitOptions = OutputLimitOptions(),
)

enum class BuildKind {
    TASKS,
    TESTS,
}

data class BuildRecord(
    val id: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val testClasses: List<String>,
    val startedAt: Instant,
    val progressTracker: BuildProgressTracker,
    val streams: CapturingStreams,
) {
    @Volatile
    var finishedAt: Instant? = null

    @Volatile
    var errorMessage: String? = null
}

class BuildExecutionManager(
    private val connectionManager: GradleConnectionManager,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gradle-build-runner").apply { isDaemon = true }
    }
    private val builds = ConcurrentHashMap<String, BuildRecord>()
    private val activeBuildId = AtomicReference<String?>(null)

    fun startBackground(
        request: BuildRunRequest,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        val activeId = activeBuildId.get()
        if (activeId != null) {
            val active = builds[activeId]
            if (active != null && active.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING) {
                error("Another build is already running (buildId=$activeId). Call gradle_get_build_status first.")
            }
        }

        val buildId = UUID.randomUUID().toString()
        val streams = CapturingStreams()
        val notifier = ProgressNotifier(exchange, progressToken)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(onUpdate = { notifier.notifyIfNeeded(tracker) })
        val record = BuildRecord(
            id = buildId,
            kind = request.kind,
            tasks = request.tasks,
            testClasses = request.testClasses,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        )
        builds[buildId] = record
        activeBuildId.set(buildId)
        pruneCompletedBuilds()

        executor.execute {
            runBuild(record, request, notifier)
        }

        return mapOf(
            "buildId" to buildId,
            "status" to BuildProgressTracker.STATUS_RUNNING,
            "kind" to request.kind.name.lowercase(),
            "tasks" to request.tasks,
            "testClasses" to request.testClasses,
            "message" to "Build started in background. Poll gradle_get_build_status with this buildId.",
        )
    }

    fun runForeground(
        request: BuildRunRequest,
        connection: ProjectConnection,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        val streams = CapturingStreams()
        val notifier = ProgressNotifier(exchange, progressToken)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(onUpdate = { notifier.notifyIfNeeded(tracker) })
        val record = BuildRecord(
            id = "foreground",
            kind = request.kind,
            tasks = request.tasks,
            testClasses = request.testClasses,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        )

        runBuild(record, request, connection, streams, tracker, notifier)

        return buildResult(record, request.outputLimit) +
            mapOf("progress" to tracker.snapshot().toResponseMap())
    }

    fun status(buildId: String?, outputLimit: OutputLimitOptions): Map<String, Any?> {
        val resolvedId = resolveBuildId(buildId)
            ?: return mapOf("status" to "not_found", "message" to "No matching build found.")
        val record = builds[resolvedId]
            ?: return mapOf("status" to "not_found", "buildId" to resolvedId)

        return buildStatusResponse(record, outputLimit)
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun resolveBuildId(buildId: String?): String? {
        if (!buildId.isNullOrBlank()) {
            return buildId
        }
        activeBuildId.get()?.let { return it }
        return builds.values.maxByOrNull { it.startedAt }?.id
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        notifier: ProgressNotifier,
    ) {
        try {
            connectionManager.withConnection { connection ->
                runBuild(record, request, connection, record.streams, record.progressTracker, notifier)
            }
        } catch (exception: Exception) {
            if (record.errorMessage == null) {
                record.progressTracker.markFailed(exception.message ?: exception.toString())
                record.errorMessage = exception.message ?: exception.toString()
            }
            if (record.finishedAt == null) {
                record.finishedAt = Instant.now()
            }
            notifier.notifyFinal(record.progressTracker)
        } finally {
            if (activeBuildId.get() == record.id) {
                activeBuildId.compareAndSet(record.id, null)
            }
            pruneCompletedBuilds()
        }
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        connection: ProjectConnection,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
        notifier: ProgressNotifier,
    ) {
        val operationLabel = when (request.kind) {
            BuildKind.TASKS -> "Gradle tasks: ${request.tasks.joinToString()}"
            BuildKind.TESTS -> "Gradle tests: ${request.testClasses.joinToString()}"
        }
        tracker.markStarting(operationLabel)
        notifier.notifyIfNeeded(tracker)

        try {
            when (request.kind) {
                BuildKind.TASKS -> {
                    val launcher = connection.newBuild()
                        .forTasks(*request.tasks.toTypedArray())
                        .addArguments(request.arguments)
                        .addJvmArguments(request.jvmArguments)
                    tracker.configureLauncher(launcher)
                    streams.applyTo(launcher)
                    launcher.run()
                }
                BuildKind.TESTS -> {
                    val launcher = connection.newTestLauncher()
                        .withJvmTestClasses(*request.testClasses.toTypedArray())
                        .addArguments(request.arguments)
                        .addJvmArguments(request.jvmArguments)
                    tracker.configureLauncher(launcher)
                    streams.applyTo(launcher)
                    launcher.run()
                }
            }
            tracker.markSucceeded()
            notifier.notifyFinal(tracker)
        } catch (exception: Exception) {
            tracker.markFailed(exception.message ?: exception.toString())
            record.errorMessage = exception.message ?: exception.toString()
            notifier.notifyFinal(tracker)
            throw exception
        } finally {
            record.finishedAt = Instant.now()
        }
    }

    private fun buildStatusResponse(record: BuildRecord, outputLimit: OutputLimitOptions): Map<String, Any?> {
        val progress = record.progressTracker.snapshot()
        val response = mutableMapOf<String, Any?>(
            "buildId" to record.id,
            "kind" to record.kind.name.lowercase(),
            "status" to progress.status,
            "startedAt" to record.startedAt.toString(),
            "finishedAt" to record.finishedAt?.toString(),
            "tasks" to record.tasks,
            "testClasses" to record.testClasses,
            "progress" to progress.toResponseMap(),
        )
        if (record.errorMessage != null) {
            response["error"] = record.errorMessage
        }
        if (progress.status != BuildProgressTracker.STATUS_RUNNING) {
            response.putAll(buildResult(record, outputLimit))
        } else {
            response.putAll(
                OutputLimiter.limitFields(record.streams.stdoutText(), outputLimit, "stdout") +
                    OutputLimiter.limitFields(record.streams.stderrText(), outputLimit, "stderr")
            )
        }
        return response
    }

    private fun buildResult(record: BuildRecord, outputLimit: OutputLimitOptions): Map<String, Any?> =
        buildMap {
            when (record.kind) {
                BuildKind.TASKS -> put("tasks", record.tasks)
                BuildKind.TESTS -> put("testClasses", record.testClasses)
            }
            putAll(OutputLimiter.limitFields(record.streams.stdoutText(), outputLimit, "stdout"))
            putAll(OutputLimiter.limitFields(record.streams.stderrText(), outputLimit, "stderr"))
            record.errorMessage?.let { put("error", it) }
        }

    private fun pruneCompletedBuilds() {
        val completed = builds.values
            .filter { it.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
        if (completed.size <= MAX_RETAINED_BUILDS) {
            return
        }
        completed.drop(MAX_RETAINED_BUILDS).forEach { builds.remove(it.id) }
    }

    private class ProgressNotifier(
        private val exchange: McpSyncServerExchange?,
        private val progressToken: Any?,
    ) {
        fun notifyIfNeeded(tracker: BuildProgressTracker) {
            if (!tracker.shouldNotifyProgress()) {
                return
            }
            sendProgress(tracker, final = false)
            sendLog(tracker)
        }

        fun notifyFinal(tracker: BuildProgressTracker) {
            sendProgress(tracker, final = true)
            sendLog(tracker)
        }

        private fun sendProgress(tracker: BuildProgressTracker, final: Boolean) {
            if (exchange == null || progressToken == null) {
                return
            }
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
            McpProgressSupport.sendProgress(
                exchange = exchange,
                progressToken = progressToken,
                progress = if (final) total.toDouble() else completed.toDouble(),
                total = total.toDouble(),
                message = message,
            )
        }

        private fun sendLog(tracker: BuildProgressTracker) {
            val snapshot = tracker.snapshot()
            val latest = snapshot.recentEvents.lastOrNull() ?: return
            val level = when (latest.eventType) {
                "TASK_FAIL", "FAIL" -> McpSchema.LoggingLevel.ERROR
                "TASK_SKIP" -> McpSchema.LoggingLevel.WARNING
                else -> McpSchema.LoggingLevel.INFO
            }
            McpProgressSupport.sendLog(exchange, "${latest.eventType}: ${latest.displayName}", level)
        }
    }

    companion object {
        private const val MAX_RETAINED_BUILDS = 5
    }
}

private fun BuildProgressSnapshot.toResponseMap(): Map<String, Any?> =
    mapOf(
        "status" to status,
        "currentOperation" to currentOperation,
        "completedTaskCount" to completedTaskCount,
        "runningTaskCount" to runningTaskCount,
        "failedTaskCount" to failedTaskCount,
        "completedTasks" to completedTasks,
        "runningTasks" to runningTasks,
        "failedTasks" to failedTasks,
        "recentEvents" to recentEvents.map { event ->
            mapOf(
                "timestamp" to event.timestamp,
                "eventType" to event.eventType,
                "displayName" to event.displayName,
                "outcome" to event.outcome,
            )
        },
        "totalEventCount" to totalEventCount,
    )
