package com.example.gradle.mcp.build.persistence

import java.io.File
import java.nio.file.Files

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
        val projectRoot = projectDirectory.canonicalFile.toPath()
        val root = recordsRoot(projectDirectory).canonicalFile
        val rootPath = root.toPath()
        if (!rootPath.startsWith(projectRoot)) {
            return null
        }
        val recordDir = File(root, buildId).canonicalFile
        val recordPath = recordDir.toPath()
        if (recordPath == rootPath || !recordPath.startsWith(rootPath)) {
            return null
        }
        return recordDir
    }

    internal fun safeRecordFile(recordDir: File, name: String): File? {
        val recordRoot = recordDir.canonicalFile.toPath()
        val candidate = File(recordDir, name)
        val path = candidate.toPath()
        if (!Files.isRegularFile(path)) {
            return null
        }
        if (Files.isSymbolicLink(path)) {
            return null
        }
        val canonical = candidate.canonicalFile.toPath()
        if (canonical != recordRoot && !canonical.startsWith(recordRoot)) {
            return null
        }
        return candidate
    }
}
