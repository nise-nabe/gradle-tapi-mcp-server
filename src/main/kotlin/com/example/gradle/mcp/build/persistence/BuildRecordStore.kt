package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressSnapshot
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
