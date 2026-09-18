package com.example.gradle.mcp.build.persistence

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object McpBuildRecordPaths {
    const val RECORDS_ROOT = ".gradle/mcp-builds"
    const val LAUNCHER_DIR = "_launcher"
    const val LAUNCHER_METADATA_FILE = "metadata.json"
    private const val MAX_BUILD_ID_LENGTH = 128

    const val GRADLE_RESULT_FILE = "gradle-result.json"
    const val MCP_RESULT_FILE = "mcp-result.json"
    const val EVENTS_FILE = "events.ndjson"
    const val STDOUT_LOG = "stdout.log"
    const val STDERR_LOG = "stderr.log"

    fun recordsRoot(projectDirectory: File): File =
        File(projectDirectory, RECORDS_ROOT)

    fun launcherMetadataFile(projectDirectory: File): File =
        File(recordsRoot(projectDirectory), LAUNCHER_DIR).resolve(LAUNCHER_METADATA_FILE)

    fun isSafeBuildId(buildId: String): Boolean {
        if (buildId.isBlank() || buildId.length > MAX_BUILD_ID_LENGTH) {
            return false
        }
        if (buildId == "." || buildId == "..") {
            return false
        }
        if ('/' in buildId || '\\' in buildId || ".." in buildId) {
            return false
        }
        if (buildId.any { it.isISOControl() }) {
            return false
        }
        return true
    }

    fun recordDirectory(projectDirectory: File, buildId: String): File? {
        if (!isSafeBuildId(buildId)) {
            return null
        }
        val projectRoot = realPath(projectDirectory)
        val rootPath = realPath(recordsRoot(projectDirectory))
        if (!rootPath.startsWith(projectRoot)) {
            return null
        }
        val recordPath = realPath(File(rootPath.toFile(), buildId))
        if (recordPath == rootPath || !recordPath.startsWith(rootPath)) {
            return null
        }
        return recordPath.toFile()
    }

    internal fun safeRecordFile(recordDir: File, name: String): File? {
        val recordRoot = realPath(recordDir)
        val candidate = File(recordDir, name)
        val path = candidate.toPath()
        if (!Files.isRegularFile(path)) {
            return null
        }
        if (Files.isSymbolicLink(path)) {
            return null
        }
        val resolved = realPath(candidate)
        if (resolved != recordRoot && !resolved.startsWith(recordRoot)) {
            return null
        }
        return candidate
    }

    /**
     * Canonical path with symlinks resolved. `File.getCanonicalFile` does not
     * follow links on Windows (unlike Unix realpath), so containment checks must
     * resolve the deepest existing ancestor with [Path.toRealPath] and re-append
     * the missing tail lexically.
     */
    private fun realPath(file: File): Path {
        var cursor = file.toPath().toAbsolutePath().normalize()
        val missingTail = ArrayDeque<Path>()
        while (!Files.exists(cursor)) {
            val parent = cursor.parent ?: break
            missingTail.addFirst(cursor.fileName)
            cursor = parent
        }
        val resolved = runCatching { cursor.toRealPath() }.getOrDefault(cursor)
        return missingTail.fold(resolved) { acc, name -> acc.resolve(name) }
    }
}
