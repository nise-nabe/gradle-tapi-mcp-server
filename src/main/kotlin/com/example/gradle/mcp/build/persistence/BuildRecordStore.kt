package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildListEntry
import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildFailureClassifier
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.build.CapturedStreamSnapshot
import com.example.gradle.mcp.build.TestProgressDetailsExtractor
import com.example.gradle.mcp.build.BuildRecord
import com.example.gradle.mcp.protocol.ProblemsSerializer
import com.example.gradle.mcp.protocol.decodeMcpJson
import com.example.gradle.mcp.protocol.decodeMcpJsonMap
import com.example.gradle.mcp.protocol.encodeMcpJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class BuildRecordStore {
    private class CachedFileValue(
        val fileKey: Any?,
        val lastModifiedMillis: Long,
        val size: Long,
        val value: Any?,
    )

    private class CachedEventLog(
        val fileKey: Any?,
        val lastModifiedMillis: Long,
        val size: Long,
        /** Bytes consumed into [events]; always ends just past a newline. */
        val offset: Long,
        /** First bytes of the file; guards against in-place rewrites. */
        val head: ByteArray,
        val events: List<DiskBuildEvent>,
        /** Events parsed from the unterminated tail; re-parsed on the next miss. */
        val tailEvents: List<DiskBuildEvent>,
    )

    private val fileValueCache = ConcurrentHashMap<Path, CachedFileValue>()
    private val eventLogCache = ConcurrentHashMap<Path, CachedEventLog>()

    fun recordDirectory(projectDirectory: File, buildId: String): File? =
        McpBuildRecordPaths.recordDirectory(projectDirectory, buildId)

    fun prepareLauncherMetadata(
        projectDirectory: File,
        buildId: String,
        taskNames: List<String>,
    ): File {
        val recordDir = recordDirectory(projectDirectory, buildId)
            ?: error("Unsafe or invalid buildId for persistence: $buildId")
        val metadata = McpBuildLauncherMetadata(
            buildId = buildId,
            recordDir = recordDir.absolutePath,
            ccInitScript = McpBuildInitScriptProvider.configurationCacheInitScriptPath(),
        )
        val metadataFile = McpBuildRecordPaths.launcherMetadataFile(projectDirectory)
        writeAtomically(metadataFile, encodeMcpJson(metadata))
        writeInitialGradleRunningState(recordDir, buildId, taskNames)
        return metadataFile
    }

    fun launcherArguments(
        projectDirectory: File,
        buildId: String,
        taskNames: List<String>,
    ): List<String> {
        val metadataFile = prepareLauncherMetadata(projectDirectory, buildId, taskNames)
        return listOf(
            "-Pmcp.launcherMetadata=${metadataFile.absolutePath}",
            "-Pmcp.ccInitScript=${McpBuildInitScriptProvider.configurationCacheInitScriptPath()}",
            "--init-script",
            McpBuildInitScriptProvider.initScriptPath(),
        )
    }

    fun writeMcpResult(record: BuildRecord, progress: BuildProgressSnapshot) {
        val projectDirectory = record.projectDirectory ?: return
        val recordDir = recordDirectory(File(projectDirectory), record.id) ?: return
        val stdout = record.streams.stdoutSnapshot()
        val stderr = record.streams.stderrSnapshot()
        val buildSummary = BuildOutputParser.parse(stdout.text)
        var status = progress.status
        var error = record.errorMessage
        var failureKind = record.failureKind?.name
        val gradleResult = readGradleResult(recordDir)
        val classified = BuildFailureClassifier.classify(
            status = status,
            kind = record.kind.name.lowercase(),
            error = error,
            progress = progress,
            stdout = stdout.text,
        )
        failureKind = classified.failureKind?.name
        error = classified.error
        val provisionalResult = McpBuildResult(
            buildId = record.id,
            kind = record.kind.name.lowercase(),
            tasks = record.tasks,
            testClasses = record.testClasses,
            testMethods = record.testMethods,
            taskPath = record.taskPath,
            includePatterns = record.includePatterns,
            taskPathInferred = record.taskPathInferred,
            projectDirectory = projectDirectory,
            startedAt = record.startedAt.toString(),
            finishedAt = (record.finishedAt ?: Instant.now()).toString(),
            status = status,
            outcome = BuildOutputParser.outcomeFromStatus(status),
            error = error,
            failureKind = failureKind,
            buildSummary = BuildOutputParser.toResponseMap(buildSummary),
            failedTaskCount = progress.failedTaskCount,
            failedTasks = progress.failedTasks,
            failedGradleTaskCount = progress.failedGradleTaskCount,
            failedGradleTasks = progress.failedGradleTasks,
            testFailures = progress.failedTests,
            failedTestCount = progress.failedTestCount,
            failedTestNames = progress.failedTestNames,
            problems = ProblemsSerializer.mergedDistinct(
                progress.problems,
                progress.liveProblems,
            ),
            stdoutTotalChars = stdout.totalChars,
            stderrTotalChars = stderr.totalChars,
        )
        val resolved = BuildPersistenceContract.resolve(
            gradleResult,
            provisionalResult,
            readEvents(recordDir),
            eventsFileLastModified(recordDir),
        )
        if (status != resolved.status &&
            resolved.terminalSource == BuildPersistenceContract.TerminalStatusSource.GRADLE
        ) {
            status = resolved.status
            val resolvedError = BuildPersistenceContract.resolveError(
                gradleResult,
                provisionalResult,
                resolved.terminalSource,
            )
            val reclassified = BuildFailureClassifier.classify(
                status = status,
                kind = record.kind.name.lowercase(),
                error = resolvedError,
                progress = progress,
                stdout = stdout.text,
            )
            error = reclassified.error
            failureKind = reclassified.failureKind?.name
        }
        val result = provisionalResult.copy(
            status = status,
            outcome = BuildOutputParser.outcomeFromStatus(status),
            error = error,
            failureKind = failureKind,
        )
        writeMcpResultFiles(recordDir, result, stdout, stderr)
    }

    fun writeMcpResultFiles(
        recordDir: File,
        result: McpBuildResult,
        stdout: CapturedStreamSnapshot,
        stderr: CapturedStreamSnapshot,
    ) {
        recordDir.mkdirs()
        writeAtomically(File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE), encodeMcpJson(result))
        writeAtomically(
            File(recordDir, McpBuildRecordPaths.STDOUT_LOG),
            stdout.text,
        )
        writeAtomically(
            File(recordDir, McpBuildRecordPaths.STDERR_LOG),
            stderr.text,
        )
    }

    fun listBuildIds(projectDirectory: File): List<String> =
        listBuildSortEntries(projectDirectory).map { it.buildId }

    internal fun listBuildSortEntries(projectDirectory: File): List<BuildSortEntry> {
        val root = McpBuildRecordPaths.recordsRoot(projectDirectory)
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles()
            ?.mapNotNull { entry ->
                if (!entry.isDirectory) {
                    return@mapNotNull null
                }
                val buildId = entry.name
                if (!McpBuildRecordPaths.isSafeBuildId(buildId)) {
                    return@mapNotNull null
                }
                val recordDir = recordDirectory(projectDirectory, buildId) ?: return@mapNotNull null
                if (!hasPersistedResult(recordDir)) {
                    return@mapNotNull null
                }
                BuildSortEntry(buildId, persistedResultSortEpoch(recordDir))
            }
            .orEmpty()
    }

    internal data class BuildSortEntry(val buildId: String, val sortEpochMillis: Long)

    private fun persistedResultSortEpoch(recordDir: File): Long {
        val mcpResult = readMcpResult(recordDir)
        val gradleResult = readGradleResult(recordDir)
        val finishedAt = mcpResult?.finishedAt ?: gradleResult?.finishedAt
        val startedAt = mcpResult?.startedAt ?: gradleResult?.startedAt
        return parseInstantEpoch(finishedAt)
            ?: parseInstantEpoch(startedAt)
            ?: persistedResultLastModified(recordDir)
    }

    private fun parseInstantEpoch(value: String?): Long? =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun persistedResultLastModified(recordDir: File): Long =
        listOfNotNull(
            McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE),
            McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE),
        ).maxOfOrNull { it.lastModified() } ?: recordDir.lastModified()

    private fun hasPersistedResult(recordDir: File): Boolean =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE) != null ||
            McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE) != null

    internal fun loadListSummary(projectDirectory: File, buildId: String): BuildListEntry? {
        val recordDir = recordDirectory(projectDirectory, buildId) ?: return null
        if (!recordDir.isDirectory) {
            return null
        }
        val gradleResult = readGradleResult(recordDir)
        val mcpResult = readMcpResult(recordDir)
        if (gradleResult == null && mcpResult == null) {
            return null
        }
        val gradleRunning = gradleResult?.status == BuildProgressTracker.STATUS_RUNNING && mcpResult != null
        val events = if (gradleRunning) {
            readEvents(recordDir)
        } else {
            emptyList()
        }
        val resolved = BuildPersistenceContract.resolve(
            gradleResult,
            mcpResult,
            events,
            if (gradleRunning) eventsFileLastModified(recordDir) else null,
        )
        val status = resolved.status
        val outcome = when (resolved.terminalSource) {
            BuildPersistenceContract.TerminalStatusSource.MCP ->
                mcpResult?.outcome ?: BuildOutputParser.outcomeFromStatus(status)
            BuildPersistenceContract.TerminalStatusSource.GRADLE,
            BuildPersistenceContract.TerminalStatusSource.NONE,
            -> BuildOutputParser.outcomeFromStatus(status)
        }
        return BuildListEntry(
            buildId = buildId,
            status = status,
            kind = mcpResult?.kind,
            tasks = mcpResult?.tasks ?: gradleResult?.taskNames.orEmpty(),
            selection = mcpResult?.selection,
            taskPathInferred = mcpResult?.taskPathInferred ?: false,
            projectDirectory = mcpResult?.projectDirectory ?: projectDirectory.absolutePath,
            startedAt = mcpResult?.startedAt ?: gradleResult?.startedAt,
            finishedAt = mcpResult?.finishedAt ?: gradleResult?.finishedAt,
            outcome = outcome,
            recordSource = "disk",
        )
    }

    fun loadArtifacts(projectDirectory: File, buildId: String): PersistedBuildArtifacts? {
        val recordDir = recordDirectory(projectDirectory, buildId) ?: return null
        if (!recordDir.isDirectory) {
            return null
        }
        val gradleResult = readGradleResult(recordDir)
        val mcpResult = readMcpResult(recordDir)
        if (gradleResult == null && mcpResult == null) {
            return null
        }
        return PersistedBuildArtifacts(
            recordDir = recordDir,
            gradleResult = gradleResult,
            mcpResult = mcpResult,
            stdout = readLogFile(recordDir, McpBuildRecordPaths.STDOUT_LOG, mcpResult?.stdoutTotalChars),
            stderr = readLogFile(recordDir, McpBuildRecordPaths.STDERR_LOG, mcpResult?.stderrTotalChars),
            events = readEvents(recordDir),
            eventsLastModified = eventsFileLastModified(recordDir),
        )
    }

    internal fun readGradleResult(recordDir: File): GradleBuildResult? =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE)
            ?.let { readJsonFile<GradleBuildResult>(it) }

    internal fun readMcpResult(recordDir: File): McpBuildResult? =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
            ?.let { readJsonFile<McpBuildResult>(it) }

    internal fun readEvents(recordDir: File): List<DiskBuildEvent> {
        val file = McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.EVENTS_FILE)
            ?: return emptyList()
        val path = file.toPath().toAbsolutePath().normalize()
        val attrs = readAttributes(file) ?: return emptyList()
        val size = attrs.size()
        val modified = attrs.lastModifiedTime().toMillis()
        val cached = eventLogCache[path]
        if (cached != null &&
            cached.size == size &&
            cached.lastModifiedMillis == modified &&
            cached.fileKey == attrs.fileKey()
        ) {
            return cached.events + cached.tailEvents
        }
        // events.ndjson is append-only, so a matching fileKey plus an unchanged
        // head prefix proves the consumed region is intact and only the appended
        // segment needs parsing. A replaced or rewritten file (different key,
        // filesystem without keys, or a head mismatch) falls back to a full read.
        val base = if (cached != null &&
            cached.fileKey != null &&
            cached.fileKey == attrs.fileKey() &&
            size >= cached.offset &&
            headMatches(file, cached.head)
        ) {
            cached
        } else {
            null
        }
        val startOffset = base?.offset ?: 0L
        val segment = readEventSegment(file, startOffset, size)
        val events = (base?.events ?: emptyList()) + segment.events
        val tailEvents = segment.tailEvents
        if (eventLogCache.size >= MAX_CACHED_FILES) {
            eventLogCache.clear()
        }
        eventLogCache[path] = CachedEventLog(
            fileKey = attrs.fileKey(),
            lastModifiedMillis = modified,
            size = size,
            offset = startOffset + segment.committedLength,
            head = base?.head ?: segment.head,
            events = events,
            tailEvents = tailEvents,
        )
        return events + tailEvents
    }

    private class ParsedEventSegment(
        val events: List<DiskBuildEvent>,
        val tailEvents: List<DiskBuildEvent>,
        /** Bytes consumed including the last newline, relative to the read offset. */
        val committedLength: Long,
        val head: ByteArray,
    )

    /**
     * Streams [file] over the range [offset, size) in fixed-size chunks and
     * parses newline-delimited events as they arrive, so the full file is
     * never materialized as a single buffer or string.
     */
    private fun readEventSegment(file: File, offset: Long, size: Long): ParsedEventSegment {
        val length = (size - offset).coerceIn(0L, Long.MAX_VALUE)
        if (length == 0L) {
            return ParsedEventSegment(emptyList(), emptyList(), 0L, ByteArray(0))
        }
        Files.newByteChannel(file.toPath()).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(EVENT_READ_CHUNK_BYTES)
            val events = ArrayList<DiskBuildEvent>()
            val head = ByteArrayOutputStream()
            val line = ByteArrayOutputStream()
            var remaining = length
            var consumed = 0L
            var committed = 0L
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = channel.read(buffer)
                if (read == -1) {
                    break
                }
                remaining -= read
                buffer.flip()
                val array = buffer.array()
                var position = buffer.position()
                val limit = buffer.limit()
                while (position < limit) {
                    var newline = position
                    while (newline < limit && array[newline] != '\n'.code.toByte()) {
                        newline++
                    }
                    val end = if (newline < limit) newline + 1 else limit
                    val count = end - position
                    if (consumed < HEAD_PREFIX_BYTES) {
                        head.write(array, position, minOf(count, HEAD_PREFIX_BYTES - consumed.toInt()))
                    }
                    line.write(array, position, count)
                    consumed += count
                    position = end
                    if (newline < limit) {
                        committed = consumed
                        val text = line.toString(StandardCharsets.UTF_8)
                            .removeSuffix("\n")
                            .removeSuffix("\r")
                        if (text.isNotBlank()) {
                            runCatching { parseEventLine(text) }.getOrNull()?.let(events::add)
                        }
                        line.reset()
                    }
                }
            }
            val tailEvents = parseEventsText(line.toString(StandardCharsets.UTF_8))
            return ParsedEventSegment(events, tailEvents, committed, head.toByteArray())
        }
    }

    private fun headMatches(file: File, head: ByteArray): Boolean {
        if (head.isEmpty()) {
            return true
        }
        val current = readBytesFrom(file, 0, head.size.toLong())
        return current.contentEquals(head)
    }

    private fun readAttributes(file: File): BasicFileAttributes? =
        runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }.getOrNull()

    private fun readBytesFrom(file: File, offset: Long, size: Long): ByteArray {
        val length = (size - offset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (length == 0) {
            return ByteArray(0)
        }
        Files.newByteChannel(file.toPath()).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // keep filling until the requested range is read or EOF
            }
            return if (buffer.hasRemaining()) {
                buffer.array().copyOf(buffer.position())
            } else {
                buffer.array()
            }
        }
    }

    private fun parseEventsText(text: String): List<DiskBuildEvent> =
        text.lineSequence()
            .mapNotNull { line ->
                if (line.isBlank()) {
                    null
                } else {
                    runCatching { parseEventLine(line) }.getOrNull()
                }
            }
            .toList()

    private fun <T> cachedFileValue(file: File, compute: (File) -> T): T {
        val path = file.toPath().toAbsolutePath().normalize()
        val attrs = readAttributes(file)
        val cached = fileValueCache[path]
        if (attrs != null && cached != null &&
            cached.size == attrs.size() &&
            cached.lastModifiedMillis == attrs.lastModifiedTime().toMillis() &&
            cached.fileKey == attrs.fileKey()
        ) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as T
        }
        val value = compute(file)
        if (attrs != null) {
            if (fileValueCache.size >= MAX_CACHED_FILES) {
                fileValueCache.clear()
            }
            fileValueCache[path] = CachedFileValue(
                fileKey = attrs.fileKey(),
                lastModifiedMillis = attrs.lastModifiedTime().toMillis(),
                size = attrs.size(),
                value = value,
            )
        }
        return value
    }

    internal fun eventsFileLastModified(recordDir: File): Instant? =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.EVENTS_FILE)
            ?.let { Instant.ofEpochMilli(it.lastModified()) }

    private fun parseEventLine(line: String): DiskBuildEvent? {
        val map = decodeMcpJsonMap(line)
        val eventType = map["type"] as? String ?: return null
        val timestamp = map["ts"] as? String ?: return null
        val displayName = map["displayName"] as? String ?: when (eventType) {
            ProgressEventTypes.BUILD_FINISHED -> "Build ${map["status"] ?: "finished"}"
            else -> eventType
        }
        return DiskBuildEvent(
            timestamp = timestamp,
            eventType = eventType,
            displayName = displayName,
            outcome = map["outcome"] as? String,
            testDetails = TestProgressDetailsExtractor.fromDiskMap(eventType, map),
        )
    }

    private inline fun <reified T> readJsonFile(file: File): T? =
        cachedFileValue(file) { target ->
            runCatching { decodeMcpJson<T>(target.readText(StandardCharsets.UTF_8)) }.getOrNull()
        }

    private fun readLogFile(
        recordDir: File,
        name: String,
        persistedTotalChars: Int? = null,
    ): CapturedStreamSnapshot {
        val file = McpBuildRecordPaths.safeRecordFile(recordDir, name)
            ?: return CapturedStreamSnapshot(text = "", totalChars = persistedTotalChars ?: 0)
        val text = cachedFileValue(file) { target -> target.readText(StandardCharsets.UTF_8) }
        val totalChars = if (persistedTotalChars != null) {
            maxOf(persistedTotalChars, text.length)
        } else {
            text.length
        }
        return CapturedStreamSnapshot(text = text, totalChars = totalChars)
    }

    private fun writeInitialGradleRunningState(
        recordDir: File,
        buildId: String,
        taskNames: List<String>,
    ) {
        // Retry paths (e.g. the TestLauncher → BuildLauncher fallback in
        // BuildExecutionManager.runTestsViaBuildLauncher) prepare launcher
        // metadata a second time for the same buildId. Once gradle-result.json
        // exists it must not be rewritten: it may already hold a terminal
        // status persisted by the init script's buildFinished hook, and even
        // for a plain "running" result a rewrite would reset startedAt and
        // append a duplicate START event to events.ndjson.
        if (McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE) != null) {
            return
        }
        recordDir.mkdirs()
        val result = GradleBuildResult(
            buildId = buildId,
            status = BuildProgressTracker.STATUS_RUNNING,
            startedAt = Instant.now().toString(),
            taskNames = taskNames,
        )
        writeAtomically(File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE), encodeMcpJson(result))
        val displayName = if (taskNames.isNotEmpty()) {
            "Gradle tasks: ${taskNames.joinToString(" ")}"
        } else {
            "Gradle build"
        }
        appendEvent(
            recordDir,
            mapOf(
                "ts" to Instant.now().toString(),
                "type" to ProgressEventTypes.START,
                "displayName" to displayName,
            ),
        )
    }

    private fun appendEvent(recordDir: File, event: Map<String, String>) {
        val line = encodeMcpJson(event) + "\n"
        val events = File(recordDir, McpBuildRecordPaths.EVENTS_FILE)
        events.parentFile?.mkdirs()
        events.appendText(line, StandardCharsets.UTF_8)
    }

    private fun writeAtomically(target: File, content: String) {
        val targetPath = target.toPath().toAbsolutePath()
        Files.createDirectories(targetPath.parent)
        // A unique temp name per write avoids interleaving when the Gradle
        // init script writes gradle-result.json from a different JVM.
        val temp = Files.createTempFile(targetPath.parent, "${target.name}.", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            moveIntoPlace(temp, targetPath)
        } catch (exception: Exception) {
            Files.deleteIfExists(temp)
            Files.writeString(targetPath, content, StandardCharsets.UTF_8)
        }
    }

    private fun moveIntoPlace(temp: Path, targetPath: Path) {
        try {
            // REPLACE_EXISTING keeps the move atomic on Windows, where
            // File.renameTo fails whenever the target already exists.
            Files.move(temp, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val MAX_CACHED_FILES = 256
        private const val HEAD_PREFIX_BYTES = 64
        private const val EVENT_READ_CHUNK_BYTES = 64 * 1024
    }
}
