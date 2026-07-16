package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleLock
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpBuildNotifier
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuildExecutionManager(
    private val connectionManager: GradleConnectionManager,
    private val buildRecordStore: BuildRecordStore = BuildRecordStore(),
) {
    private var executor: ExecutorService = newBuildExecutor()
    private val builds = ConcurrentHashMap<String, BuildRecord>()
    private val lastCompletedBuildSnapshots = ConcurrentHashMap<String, CompletedBuildSnapshot>()
    private val projectQueues = ConcurrentHashMap<String, ArrayDeque<PendingBuild>>()

    fun startBackground(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
        queueIfBusy: Boolean = false,
    ): Map<String, Any?> {
        connectionManager.requireConnection(request.projectDirectory)

        val start = newBuildStart(request, notifier)
        val projectDirectory = request.projectDirectory

        synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
            if (!hasRunningBuild(projectDirectory) && !hasQueuedBuild(projectDirectory)) {
                return startImmediately(start, request, projectDirectory)
            }
            if (!queueIfBusy) {
                throw buildAlreadyRunningForProjectException(projectDirectory)
            }
            if (countQueued(projectDirectory) >= MAX_QUEUED_PER_PROJECT) {
                throw buildQueueFullException(projectDirectory)
            }
            return enqueueBackground(start, request, projectDirectory)
        }
    }

    suspend fun runForeground(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
        foregroundDetachTimeoutMs: Long = DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS,
    ): Map<String, Any?> {
        connectionManager.requireConnection(request.projectDirectory)

        val start = newBuildStart(request, notifier)
        val buildId = registerBuildStart(start)
        val completion = CountDownLatch(1)

        try {
            executor.execute {
                try {
                    runBuild(start.record, request, start.notifier)
                } finally {
                    completion.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(ProjectLifecycleLock.forProject(request.projectDirectory)) {
                builds.remove(buildId)
            }
            throw maxConcurrentBuildsException()
        }

        return withContext(NonCancellable) {
            try {
                val completedInTime = awaitBuildCompletion(completion, foregroundDetachTimeoutMs)
                if (completedInTime) {
                    BuildStatusAssembler.assemble(
                        view = BuildStatusView.fromRecord(start.record),
                        outputLimit = request.outputLimit,
                        progressOptions = request.progressOptions,
                        style = BuildStatusResponseStyle.FOREGROUND,
                    )
                } else {
                    detachedForegroundResponse(start.record, request)
                }
            } catch (_: InterruptedException) {
                Thread.interrupted()
                detachedForegroundResponse(start.record, request)
            } finally {
                pruneCompletedBuilds()
            }
        }
    }

    private fun awaitBuildCompletion(completion: CountDownLatch, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) {
            completion.await()
            return true
        }
        return completion.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun cancelBuild(buildId: String, projectDirectoryHint: File? = null): Map<String, Any?> {
        val record = builds[buildId]
        if (record == null) {
            throw McpException(McpErrorCode.INVALID_ARGUMENT, "Build not found: $buildId")
        }
        projectDirectoryHint?.let { hint ->
            val recordProject = record.projectDirectory
            if (recordProject != null && !ProjectDirectoryResolver.sameProject(recordProject, hint)) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Build $buildId does not belong to project ${hint.path}",
                )
            }
        }
        val status = record.progressTracker.snapshot().status
        when (status) {
            BuildProgressTracker.STATUS_QUEUED -> {
                synchronized(ProjectLifecycleLock.forProject(File(record.projectDirectory!!))) {
                    removeFromQueue(record)
                    finalizeQueuedBuild(record, BuildTerminalOutcome.Cancelled("Build cancelled"))
                    return buildMap {
                        put("buildId", buildId)
                        put("status", BuildProgressTracker.STATUS_NOT_RUNNING)
                        put("terminalStatus", BuildProgressTracker.STATUS_CANCELLED)
                        put("cancelled", true)
                        put("outcome", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_CANCELLED))
                        record.finishedAt?.toString()?.let { put("finishedAt", it) }
                        put("message", "Queued build cancelled.")
                    }
                }
            }
            BuildProgressTracker.STATUS_RUNNING -> {
                record.cancellationTokenSource.cancel()
                return mapOf(
                    "buildId" to buildId,
                    "status" to BuildProgressTracker.STATUS_RUNNING,
                    "message" to
                        "Cancellation requested. Poll gradle_get_build_status until status is no longer running.",
                )
            }
            else -> return buildMap {
                put("buildId", buildId)
                put("status", BuildProgressTracker.STATUS_NOT_RUNNING)
                put("terminalStatus", status)
                put("cancelled", false)
                put("outcome", BuildOutputParser.outcomeFromStatus(status))
                record.finishedAt?.toString()?.let { put("finishedAt", it) }
                put("message", "Build already finished; nothing to cancel.")
            }
        }
    }

    fun status(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
        waitOptions: BuildStatusWaitOptions = BuildStatusWaitOptions(),
    ): Map<String, Any?> {
        if (!waitOptions.waitUntilComplete) {
            return statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        }
        val waitStartedAt = System.currentTimeMillis()
        val deadline = waitStartedAt + waitOptions.waitTimeoutMs
        var latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        while (
            latest["status"] == BuildProgressTracker.STATUS_RUNNING ||
                latest["status"] == BuildProgressTracker.STATUS_QUEUED
        ) {
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                return latest + mapOf(
                    "waitTimedOut" to true,
                    "waitedMs" to (now - waitStartedAt),
                    "hint" to BuildStatusWaitOptions.WAIT_TIMEOUT_HINT,
                )
            }
            Thread.sleep(waitOptions.pollIntervalMs)
            latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        }
        return latest
    }

    private fun statusOnce(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
    ): Map<String, Any?> {
        val record = builds[buildId]
        projectDirectoryHint?.let { hint ->
            val recordProject = record?.projectDirectory
            if (record != null && recordProject != null &&
                !ProjectDirectoryResolver.sameProject(recordProject, hint)
            ) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Build $buildId does not belong to project ${hint.path}",
                )
            }
        }
        val projectDirectory = record?.projectDirectory?.let(::File)
            ?: projectDirectoryHint
            ?: connectionManager.defaultProjectDirectory()
            ?: ProjectDirectoryResolver.workspaceFromEnvironment()
        val artifacts = projectDirectory?.let { buildRecordStore.loadArtifacts(it, buildId) }

        val view = when {
            record != null && artifacts != null -> {
                BuildStatusMerger.merge(
                    BuildStatusView.fromRecord(record),
                    PersistedBuildViewFactory.fromArtifacts(buildId, artifacts),
                )
            }
            record != null -> BuildStatusView.fromRecord(record)
            artifacts != null -> PersistedBuildViewFactory.fromArtifacts(buildId, artifacts)
            else -> return mapOf("status" to "not_found", "buildId" to buildId)
        }
        return withQueueFields(
            response = BuildStatusAssembler.assemble(view, outputLimit, progressOptions),
            buildId = buildId,
            projectDirectory = record?.projectDirectory?.let(::File) ?: projectDirectory,
            status = view.status,
        )
    }

    fun listBuilds(projectDirectoryHint: File?, limit: Int): Map<String, Any?> {
        val cappedLimit = limit.coerceIn(1, MAX_LIST_BUILDS)
        val diskProjectDirectory = resolveProjectDirectory(projectDirectoryHint)

        val entries = LinkedHashMap<String, BuildListEntry>()
        builds.values
            .asSequence()
            .filter { record -> record.matchesProject(projectDirectoryHint) }
            .forEach { record ->
                entries[record.id] = listEntryFromRecord(record, diskProjectDirectory)
            }

        val totalAvailable = if (diskProjectDirectory != null) {
            val diskBuildIds = buildRecordStore.listBuildIds(diskProjectDirectory)
            entries.size + diskBuildIds.count { it !in entries }
        } else {
            entries.size
        }

        if (diskProjectDirectory != null) {
            val diskCandidates = buildRecordStore.listBuildSortEntries(diskProjectDirectory)
                .filter { it.buildId !in entries }
            val topDiskIds = buildList {
                entries.forEach { (buildId, entry) ->
                    add(buildId to entry.sortInstant().toEpochMilli())
                }
                diskCandidates.forEach { candidate ->
                    add(candidate.buildId to candidate.sortEpochMillis)
                }
            }
                .sortedByDescending { it.second }
                .take(cappedLimit)
                .map { it.first }
                .toSet()
            diskCandidates
                .filter { it.buildId in topDiskIds }
                .forEach { candidate ->
                    buildRecordStore.loadListSummary(diskProjectDirectory, candidate.buildId)?.let { summary ->
                        entries[candidate.buildId] = summary
                    }
                }
        }

        val sorted = entries.values.sortedByDescending { it.sortInstant() }
        val limited = sorted.take(cappedLimit)
        return buildMap {
            put("builds", limited.map { it.toResponseMap() })
            (projectDirectoryHint ?: diskProjectDirectory)?.absolutePath?.let { put("projectDirectory", it) }
            put("totalAvailable", totalAvailable)
            put("truncated", totalAvailable > cappedLimit)
        }
    }

    private fun resolveProjectDirectory(hint: File?): File? =
        hint
            ?: connectionManager.defaultProjectDirectory()
            ?: ProjectDirectoryResolver.workspaceFromEnvironment()

    private fun listEntryFromRecord(record: BuildRecord, projectDirectory: File?): BuildListEntry {
        val snapshot = record.progressTracker.snapshot()
        var status = snapshot.status
        var outcome = BuildOutputParser.outcomeFromStatus(status)
        var recordSource = "memory"
        var statusSource: String? = null
        val memoryStatus = status
        val artifactProject = record.projectDirectory?.let(::File) ?: projectDirectory
        if (artifactProject != null && status != BuildProgressTracker.STATUS_RUNNING) {
            buildRecordStore.loadArtifacts(artifactProject, record.id)?.let { artifacts ->
                val merged = BuildStatusMerger.merge(
                    BuildStatusView.fromRecord(record),
                    PersistedBuildViewFactory.fromArtifacts(record.id, artifacts),
                )
                status = merged.status
                outcome = merged.outcome ?: BuildOutputParser.outcomeFromStatus(status)
                if (merged.status != memoryStatus) {
                    recordSource = "merged"
                    statusSource = merged.statusSource
                }
            }
        }
        return BuildListEntry(
            buildId = record.id,
            status = status,
            kind = record.kind.name.lowercase(),
            tasks = record.tasks,
            selection = record.selection,
            projectDirectory = record.projectDirectory,
            startedAt = record.startedAt.toString(),
            finishedAt = record.finishedAt?.toString(),
            outcome = outcome,
            recordSource = recordSource,
            statusSource = statusSource,
            queuePosition = queuePosition(record.id, artifactProject),
            queuedBehindBuildId = queuedBehindBuildId(record.id, artifactProject),
        )
    }

    fun hasActiveBuild(projectDirectory: File? = null): Boolean =
        hasRunningBuild(projectDirectory) || hasQueuedBuild(projectDirectory)

    fun hasRunningBuild(projectDirectory: File? = null): Boolean =
        builds.values.any { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                record.matchesProject(projectDirectory)
        }

    fun hasQueuedBuild(projectDirectory: File? = null): Boolean =
        builds.values.any { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_QUEUED &&
                record.matchesProject(projectDirectory)
        }

    fun resetBuildState(reason: String, projectDirectory: File? = null) {
        synchronized(lifecycleLockFor(projectDirectory)) {
            markQueuedBuildsCancelled(reason, projectDirectory)
            markRunningBuildsCancelled(reason, projectDirectory)
            if (shouldReplaceExecutor(projectDirectory)) {
                replaceBuildExecutor()
            }
        }
    }

    fun onDisconnect(projectDirectory: File? = null) {
        resetBuildState("Gradle connection closed", projectDirectory)
        if (projectDirectory == null) {
            lastCompletedBuildSnapshots.clear()
        } else {
            lastCompletedBuildSnapshots.remove(ProjectDirectoryResolver.canonicalKey(projectDirectory))
        }
    }

    private fun shouldReplaceExecutor(projectDirectory: File?): Boolean =
        projectDirectory == null || connectionManager.connectedProjectDirectories().isEmpty()

    fun shutdown() {
        val executorToAwait = synchronized(ProjectLifecycleLock.global()) {
            markQueuedBuildsCancelled("Server shutting down")
            markRunningBuildsCancelled("Server shutting down")
            val currentExecutor = executor
            currentExecutor.shutdown()
            currentExecutor
        }
        try {
            if (!executorToAwait.awaitTermination(5, TimeUnit.SECONDS)) {
                synchronized(ProjectLifecycleLock.global()) {
                    executorToAwait.shutdownNow()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun markQueuedBuildsCancelled(reason: String, projectDirectory: File? = null) {
        builds.values
            .filter { record ->
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_QUEUED &&
                    record.matchesProject(projectDirectory)
            }
            .forEach { record ->
                removeFromQueue(record)
                finalizeQueuedBuild(record, BuildTerminalOutcome.Cancelled(reason))
            }
    }

    private fun markRunningBuildsCancelled(reason: String, projectDirectory: File? = null) {
        builds.values
            .filter { record ->
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                    record.matchesProject(projectDirectory)
            }
            .forEach { record ->
                record.cancellationTokenSource.cancel()
                finalizeBuild(record, BuildTerminalOutcome.Cancelled(reason))
            }
    }

    private sealed interface BuildTerminalOutcome {
        data object Succeeded : BuildTerminalOutcome

        data class Failed(val message: String) : BuildTerminalOutcome

        data class Cancelled(val message: String) : BuildTerminalOutcome
    }

    private fun finalizeBuild(record: BuildRecord, outcome: BuildTerminalOutcome): Boolean {
        if (record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING) {
            return false
        }
        when (outcome) {
            BuildTerminalOutcome.Succeeded -> record.progressTracker.markSucceeded()
            is BuildTerminalOutcome.Failed -> record.progressTracker.markFailed(outcome.message)
            is BuildTerminalOutcome.Cancelled -> record.progressTracker.markCancelled(outcome.message)
        }
        val expectedStatus = when (outcome) {
            BuildTerminalOutcome.Succeeded -> BuildProgressTracker.STATUS_SUCCEEDED
            is BuildTerminalOutcome.Failed -> BuildProgressTracker.STATUS_FAILED
            is BuildTerminalOutcome.Cancelled -> BuildProgressTracker.STATUS_CANCELLED
        }
        if (record.progressTracker.snapshot().status != expectedStatus) {
            return false
        }
        if (outcome is BuildTerminalOutcome.Failed && record.errorMessage == null) {
            record.errorMessage = outcome.message
        }
        if (outcome is BuildTerminalOutcome.Cancelled && record.errorMessage == null) {
            record.errorMessage = outcome.message
        }
        val classified = BuildFailureClassifier.classify(
            status = record.progressTracker.snapshot().status,
            kind = record.kind.name.lowercase(),
            error = record.errorMessage,
            progress = record.progressTracker.snapshot(),
            stdout = record.streams.stdoutSnapshot().text,
        )
        record.failureKind = classified.failureKind
        if (classified.error != record.errorMessage) {
            record.errorMessage = classified.error
        }
        if (record.finishedAt == null) {
            record.finishedAt = Instant.now()
        }
        rememberCompletedBuild(record, outcome)
        buildRecordStore.writeMcpResult(record, record.progressTracker.snapshot())
        afterBuildSlotFreed(record)
        return true
    }

    private fun finalizeQueuedBuild(record: BuildRecord, outcome: BuildTerminalOutcome.Cancelled): Boolean {
        if (record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_QUEUED) {
            return false
        }
        record.progressTracker.markCancelled(outcome.message)
        record.errorMessage = outcome.message
        record.finishedAt = Instant.now()
        return true
    }

    private fun afterBuildSlotFreed(record: BuildRecord) {
        val projectDirectory = record.projectDirectory?.let(::File) ?: return
        drainProjectQueue(projectDirectory)
        drainAllProjectQueues()
    }

    internal fun lastCompletedBuildSnapshot(projectDirectory: File): CompletedBuildSnapshot? =
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(projectDirectory)]

    private fun rememberCompletedBuild(record: BuildRecord, outcome: BuildTerminalOutcome) {
        val buildOutcome = when (outcome) {
            BuildTerminalOutcome.Succeeded -> "SUCCESS"
            is BuildTerminalOutcome.Failed -> "FAILED"
            is BuildTerminalOutcome.Cancelled -> "CANCELLED"
        }
        val projectDirectory = record.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] =
            CompletedBuildSnapshot(
            buildId = record.id,
            kind = record.kind,
            tasks = record.tasks,
            testClasses = record.testClasses,
            finishedAt = record.finishedAt ?: Instant.now(),
            outcome = buildOutcome,
            stdout = record.streams.stdoutSnapshot().text,
            projectDirectory = record.projectDirectory,
        )
    }

    private fun newBuildStart(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
    ): BuildStart {
        val streams = CapturingStreams()
        val progressNotifier = BuildProgressNotifier(notifier)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(
            trackDownloads = request.progressOptions.includeDownloads,
            onUpdate = { progressNotifier.notifyIfNeeded(tracker) },
        )
        val record = BuildRecord(
            id = UUID.randomUUID().toString(),
            kind = request.kind,
            tasks = request.tasks,
            selection = request.selection,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = request.projectDirectory.absolutePath,
        )
        return BuildStart(record, progressNotifier)
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        notifier: BuildProgressNotifier,
    ) {
        try {
            connectionManager.withConnection(request.projectDirectory) { connection ->
                runBuild(record, request, connection, record.streams, record.progressTracker, notifier)
            }
        } catch (exception: Exception) {
            finalizeBuild(record, terminalOutcomeFor(exception, record))
            notifier.notifyFinal(record.progressTracker)
        } finally {
            pruneCompletedBuilds()
        }
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        connection: ProjectConnection,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
        notifier: BuildProgressNotifier,
    ) {
        val operationLabel = when (request.kind) {
            BuildKind.TASKS -> "Gradle tasks: ${request.tasks.joinToString()}"
            BuildKind.TESTS -> describeTestOperation(request)
        }
        tracker.markStarting(operationLabel)
        notifier.notifyIfNeeded(tracker)

        try {
            when (request.kind) {
                BuildKind.TASKS -> {
                    val launcher = connection.newBuild()
                        .forTasks(*request.tasks.toTypedArray())
                    configureLauncher(launcher, record, request, streams, tracker)
                    launcher.run()
                }
                BuildKind.TESTS -> {
                    val launcher = configureTestLauncher(connection.newTestLauncher(), request)
                    configureLauncher(launcher, record, request, streams, tracker)
                    launcher.run()
                }
            }
            finalizeBuild(record, BuildTerminalOutcome.Succeeded)
            notifier.notifyFinal(tracker)
        } catch (exception: Exception) {
            val outcome = terminalOutcomeFor(exception, record)
            finalizeBuild(record, outcome)
            notifier.notifyFinal(tracker)
            throw exception
        }
    }

    private fun terminalOutcomeFor(exception: Exception, record: BuildRecord? = null): BuildTerminalOutcome {
        if (exception is BuildCancelledException) {
            return BuildTerminalOutcome.Cancelled(exception.message ?: "Build cancelled")
        }
        if (isInterruptRelated(exception)) {
            record?.requestCancellationIfNeeded()
            return BuildTerminalOutcome.Cancelled(
                exception.message?.takeIf { it.isNotBlank() } ?: "Build interrupted",
            )
        }
        return BuildTerminalOutcome.Failed(exception.message ?: exception.toString())
    }

    private fun isInterruptRelated(exception: Exception): Boolean =
        exception is InterruptedException || exception.cause is InterruptedException

    private fun BuildRecord.requestCancellationIfNeeded() {
        if (!cancellationTokenSource.token().isCancellationRequested) {
            cancellationTokenSource.cancel()
        }
    }

    private fun detachedForegroundResponse(record: BuildRecord, request: BuildRunRequest): Map<String, Any?> =
        buildMap {
            put("buildId", record.id)
            put("status", BuildProgressTracker.STATUS_RUNNING)
            put("kind", request.kind.name.lowercase())
            put("tasks", request.tasks)
            put("testClasses", request.testClasses)
            putTestRunSelection(request.selection)
            put("detached", true)
            put(
                "message",
                "MCP client request ended; build continues in background. " +
                    "Poll gradle_get_build_status with this buildId.",
            )
        }

    private fun configureLauncher(
        launcher: ConfigurableLauncher<*>,
        record: BuildRecord,
        request: BuildRunRequest,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
    ) {
        GradleArgumentPolicy.validateUserBuildArguments(request.arguments, request.jvmArguments)
        val persistenceArguments = record.projectDirectory
            ?.let { buildRecordStore.launcherArguments(File(it), record.id, request.tasks) }
            .orEmpty()
        launcher.addArguments(*(request.arguments + persistenceArguments).toTypedArray())
        launcher.addJvmArguments(*request.jvmArguments.toTypedArray())
        launcher.withCancellationToken(record.cancellationTokenSource.token())
        launcher.withDetailedFailure()
        tracker.configureLauncher(launcher, request.progressOptions.includeProblems)
        streams.applyTo(launcher)
    }

    private fun pruneCompletedBuilds() {
        val completed = builds.values
            .filter { record ->
                val status = record.progressTracker.snapshot().status
                status != BuildProgressTracker.STATUS_RUNNING &&
                    status != BuildProgressTracker.STATUS_QUEUED
            }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
        if (completed.size <= MAX_RETAINED_BUILDS) {
            return
        }
        completed.drop(MAX_RETAINED_BUILDS).forEach { builds.remove(it.id) }
    }

    private class BuildProgressNotifier(
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

    private data class BuildStart(
        val record: BuildRecord,
        val notifier: BuildProgressNotifier,
    )

    private data class PendingBuild(
        val start: BuildStart,
        val request: BuildRunRequest,
    )

    private fun startImmediately(
        start: BuildStart,
        request: BuildRunRequest,
        projectDirectory: File,
    ): Map<String, Any?> {
        val buildId = registerImmediateBuildStart(start, projectDirectory)
        try {
            executor.execute {
                runBuild(start.record, request, start.notifier)
            }
        } catch (_: RejectedExecutionException) {
            synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
                builds.remove(buildId)
            }
            throw maxConcurrentBuildsException()
        }
        return runningBackgroundResponse(start.record.id, request)
    }

    private fun enqueueBackground(
        start: BuildStart,
        request: BuildRunRequest,
        projectDirectory: File,
    ): Map<String, Any?> {
        check(start.record.progressTracker.markQueued()) {
            "Failed to mark build ${start.record.id} as queued"
        }
        builds[start.record.id] = start.record
        val projectKey = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        projectQueues.computeIfAbsent(projectKey) { ArrayDeque() }.addLast(PendingBuild(start, request))
        pruneCompletedBuilds()
        return queuedBackgroundResponse(start.record.id, request, projectDirectory)
    }

    private fun runningBackgroundResponse(buildId: String, request: BuildRunRequest): Map<String, Any?> =
        buildMap {
            put("buildId", buildId)
            put("status", BuildProgressTracker.STATUS_RUNNING)
            put("kind", request.kind.name.lowercase())
            put("tasks", request.tasks)
            put("testClasses", request.testClasses)
            putTestRunSelection(request.selection)
            put(
                "message",
                "Build started in background. Poll gradle_get_build_status with this buildId.",
            )
        }

    private fun queuedBackgroundResponse(
        buildId: String,
        request: BuildRunRequest,
        projectDirectory: File,
    ): Map<String, Any?> =
        buildMap {
            put("buildId", buildId)
            put("status", BuildProgressTracker.STATUS_QUEUED)
            put("kind", request.kind.name.lowercase())
            put("tasks", request.tasks)
            put("testClasses", request.testClasses)
            putTestRunSelection(request.selection)
            queuePosition(buildId, projectDirectory)?.let { put("queuePosition", it) }
            queuedBehindBuildId(buildId, projectDirectory)?.let { put("queuedBehindBuildId", it) }
            put(
                "message",
                "Build queued. Poll gradle_get_build_status with this buildId until status is running or terminal.",
            )
        }

    private fun countQueued(projectDirectory: File): Int =
        projectQueues[ProjectDirectoryResolver.canonicalKey(projectDirectory)]?.size ?: 0

    private fun queuePosition(buildId: String, projectDirectory: File?): Int? {
        if (projectDirectory == null) {
            return null
        }
        val queue = projectQueues[ProjectDirectoryResolver.canonicalKey(projectDirectory)] ?: return null
        val index = queue.indexOfFirst { it.start.record.id == buildId }
        return if (index >= 0) index + 1 else null
    }

    private fun queuedBehindBuildId(buildId: String, projectDirectory: File?): String? {
        if (projectDirectory == null) {
            return null
        }
        val runningBuildId = builds.values.firstOrNull { record ->
            record.matchesProject(projectDirectory) &&
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING
        }?.id
        val queue = projectQueues[ProjectDirectoryResolver.canonicalKey(projectDirectory)] ?: return runningBuildId
        val index = queue.indexOfFirst { it.start.record.id == buildId }
        if (index < 0) {
            return runningBuildId
        }
        if (index == 0) {
            return runningBuildId
        }
        return queue.toList()[index - 1].start.record.id
    }

    private fun withQueueFields(
        response: Map<String, Any?>,
        buildId: String,
        projectDirectory: File?,
        status: String,
    ): Map<String, Any?> {
        if (status != BuildProgressTracker.STATUS_QUEUED || projectDirectory == null) {
            return response
        }
        return response + buildMap {
            queuePosition(buildId, projectDirectory)?.let { put("queuePosition", it) }
            queuedBehindBuildId(buildId, projectDirectory)?.let { put("queuedBehindBuildId", it) }
        }
    }

    private fun removeFromQueue(record: BuildRecord) {
        val projectDirectory = record.projectDirectory?.let(::File) ?: return
        val projectKey = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        projectQueues[projectKey]?.removeIf { it.start.record.id == record.id }
        if (projectQueues[projectKey]?.isEmpty() == true) {
            projectQueues.remove(projectKey)
        }
    }

    private fun drainAllProjectQueues() {
        projectQueues.keys.toList().forEach { projectKey ->
            drainProjectQueue(File(projectKey))
        }
    }

    private fun drainProjectQueue(projectDirectory: File) {
        val projectKey = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        while (true) {
            val pending = synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
                if (hasRunningBuild(projectDirectory)) {
                    return
                }
                val queue = projectQueues[projectKey] ?: return
                queue.peekFirst() ?: run {
                    projectQueues.remove(projectKey)
                    return
                }
            }
            if (!tryStartPending(pending, projectDirectory)) {
                return
            }
            synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
                projectQueues[projectKey]?.pollFirst()
                if (projectQueues[projectKey]?.isEmpty() == true) {
                    projectQueues.remove(projectKey)
                }
            }
        }
    }

    private fun tryStartPending(pending: PendingBuild, projectDirectory: File): Boolean {
        if (!pending.start.record.progressTracker.markDequeued()) {
            return false
        }
        return try {
            executor.execute {
                runBuild(pending.start.record, pending.request, pending.start.notifier)
            }
            true
        } catch (_: RejectedExecutionException) {
            pending.start.record.progressTracker.markQueued()
            false
        }
    }

    internal fun seedRunningBuildForTests(record: BuildRecord) {
        builds[record.id] = record
    }

    internal fun seedQueuedBuildForTests(record: BuildRecord, request: BuildRunRequest) {
        builds[record.id] = record
        val projectDirectory = record.projectDirectory?.let(::File) ?: return
        val projectKey = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        projectQueues.computeIfAbsent(projectKey) { ArrayDeque() }
            .addLast(PendingBuild(BuildStart(record, BuildProgressNotifier(null)), request))
    }

    internal fun completeBuildForTests(buildId: String, succeeded: Boolean = true): Boolean {
        val record = builds[buildId] ?: return false
        val outcome = if (succeeded) {
            BuildTerminalOutcome.Succeeded
        } else {
            BuildTerminalOutcome.Failed("Build failed")
        }
        return finalizeBuild(record, outcome)
    }

    internal fun seedLastCompletedBuildForTests(snapshot: CompletedBuildSnapshot) {
        val projectDirectory = snapshot.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] = snapshot
    }

    internal fun maxConcurrentBackgroundBuilds(): Int = MAX_CONCURRENT_BUILDS

    private fun newBuildExecutor(): ExecutorService {
        val threadCounter = AtomicInteger()
        return ThreadPoolExecutor(
            MAX_CONCURRENT_BUILDS,
            MAX_CONCURRENT_BUILDS,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable ->
                Thread(runnable, "gradle-build-runner-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private fun replaceBuildExecutor() {
        val oldExecutor = executor
        oldExecutor.shutdown()
        try {
            if (!oldExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                oldExecutor.shutdownNow()
                oldExecutor.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            oldExecutor.shutdownNow()
        }
        executor = newBuildExecutor()
    }

    private fun registerImmediateBuildStart(start: BuildStart, projectDirectory: File): String {
        synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
            if (hasActiveBuild(projectDirectory)) {
                throw buildAlreadyRunningForProjectException(projectDirectory)
            }
            builds[start.record.id] = start.record
            pruneCompletedBuilds()
            return start.record.id
        }
    }

    private fun registerBuildStart(start: BuildStart): String {
        val projectDirectory = start.record.projectDirectory?.let { File(it) }
            ?: error("Build record missing projectDirectory")
        return registerImmediateBuildStart(start, projectDirectory)
    }

    private fun lifecycleLockFor(projectDirectory: File?): Any =
        if (projectDirectory != null) {
            ProjectLifecycleLock.forProject(projectDirectory)
        } else {
            ProjectLifecycleLock.global()
        }

    private fun buildAlreadyRunningForProjectException(projectDirectory: File): McpException =
        McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "A Gradle build is already running for ${projectDirectory.path}. " +
                "Poll gradle_get_build_status with the active buildId, call gradle_cancel_build to stop it, " +
                "wait for it to finish, or pass queueIfBusy=true with background=true to enqueue.",
        )

    private fun buildQueueFullException(projectDirectory: File): McpException =
        McpException(
            McpErrorCode.BUILD_QUEUE_FULL,
            "Build queue is full for ${projectDirectory.path} " +
                "(max $MAX_QUEUED_PER_PROJECT queued builds). " +
                "Poll or cancel queued builds with gradle_get_build_status / gradle_cancel_build.",
        )

    private fun maxConcurrentBuildsException(): McpException =
        McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Maximum concurrent builds ($MAX_CONCURRENT_BUILDS) reached. " +
                "Poll gradle_get_build_status or wait for a build to finish.",
        )

    companion object {
        private val MAX_CONCURRENT_BUILDS = maxOf(4, Runtime.getRuntime().availableProcessors())
        private const val MAX_QUEUED_PER_PROJECT = 3
        private const val MAX_RETAINED_BUILDS = 10
        internal const val DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_LIST_BUILDS = 20
        internal const val MAX_LIST_BUILDS = 100
    }
}
