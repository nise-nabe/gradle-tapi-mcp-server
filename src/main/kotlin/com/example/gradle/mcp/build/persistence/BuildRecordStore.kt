package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildListEntry
import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildRecord
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.build.CapturedStreamSnapshot
import com.example.gradle.mcp.protocol.mcpObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

class BuildRecordStore(
    private val objectMapper: JsonMapper = mcpObjectMapper(),
) {
    fun recordDirectory(projectDirectory: File, buildId: String): File? =
        McpBuildRecordPaths.recordDirectory(projectDirectory, buildId)

    fun launcherArguments(projectDirectory: File, buildId: String): List<String> {
        val recordDir = recordDirectory(projectDirectory, buildId)
            ?: error("Unsafe or invalid buildId for persistence: $buildId")
        return listOf(
            "-Pmcp.buildId=$buildId",
            "-Pmcp.recordDir=${recordDir.absolutePath}",
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
        val result = McpBuildResult(
            buildId = record.id,
            kind = record.kind.name.lowercase(),
            tasks = record.tasks,
            testClasses = record.testClasses,
            testMethods = record.testMethods,
            taskPath = record.taskPath,
            includePatterns = record.includePatterns,
            projectDirectory = projectDirectory,
            startedAt = record.startedAt.toString(),
            finishedAt = (record.finishedAt ?: Instant.now()).toString(),
            status = progress.status,
            outcome = BuildOutputParser.outcomeFromStatus(progress.status),
            error = record.errorMessage,
            buildSummary = BuildOutputParser.toResponseMap(buildSummary),
            failedTaskCount = progress.failedTaskCount,
            failedTasks = progress.failedTasks,
            stdoutTotalChars = stdout.totalChars,
            stderrTotalChars = stderr.totalChars,
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
        writeAtomically(File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE), objectMapper.writeValueAsString(result))
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

    fun listRecentBuildIds(
        projectDirectory: File,
        excludeBuildIds: Set<String>,
        limit: Int,
    ): List<String> {
        if (limit <= 0) {
            return emptyList()
        }
        return listBuildSortEntries(projectDirectory)
            .asSequence()
            .filter { it.buildId !in excludeBuildIds }
            .sortedByDescending { it.sortEpochMillis }
            .take(limit)
            .map { it.buildId }
            .toList()
    }

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
        val events = if (gradleResult?.status == BuildProgressTracker.STATUS_RUNNING && mcpResult != null) {
            readEvents(recordDir)
        } else {
            emptyList()
        }
        val resolved = BuildPersistenceContract.resolve(gradleResult, mcpResult, events)
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
            testClasses = mcpResult?.testClasses.orEmpty(),
            testMethods = mcpResult?.testMethods.orEmpty(),
            taskPath = mcpResult?.taskPath,
            includePatterns = mcpResult?.includePatterns.orEmpty(),
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
        )
    }

    internal fun readGradleResult(recordDir: File): GradleBuildResult? =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE)
            ?.let { readJsonFile(it) }

    internal fun readMcpResult(recordDir: File): McpBuildResult? =
        McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
            ?.let { readJsonFile(it) }

    internal fun readEvents(recordDir: File): List<DiskBuildEvent> {
        val file = McpBuildRecordPaths.safeRecordFile(recordDir, McpBuildRecordPaths.EVENTS_FILE)
            ?: return emptyList()
        return file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) {
                    return@mapNotNull null
                }
                runCatching { parseEventLine(line) }.getOrNull()
            }.toList()
        }
    }

    private fun parseEventLine(line: String): DiskBuildEvent? {
        val map = objectMapper.readValue<Map<String, Any?>>(line)
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
        )
    }

    private inline fun <reified T> readJsonFile(file: File): T? =
        runCatching { objectMapper.readValue<T>(file.readText(StandardCharsets.UTF_8)) }.getOrNull()

    private fun readLogFile(
        recordDir: File,
        name: String,
        persistedTotalChars: Int? = null,
    ): CapturedStreamSnapshot {
        val file = McpBuildRecordPaths.safeRecordFile(recordDir, name)
            ?: return CapturedStreamSnapshot(text = "", totalChars = persistedTotalChars ?: 0)
        val text = file.readText(StandardCharsets.UTF_8)
        val totalChars = if (persistedTotalChars != null) {
            maxOf(persistedTotalChars, text.length)
        } else {
            text.length
        }
        return CapturedStreamSnapshot(text = text, totalChars = totalChars)
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content, StandardCharsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            temp.delete()
        }
    }
}
