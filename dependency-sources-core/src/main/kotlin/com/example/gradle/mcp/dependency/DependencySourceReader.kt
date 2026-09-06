package com.example.gradle.mcp.dependency

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class ReadSourceRequest(
    val artifact: DependencyArtifactRef,
    val path: String,
    val line: Int? = null,
    val contextLines: Int = DEFAULT_CONTEXT_LINES,
    val maxLines: Int = DEFAULT_MAX_LINES,
    val sourceRoot: File? = null,
    val gradleUserHome: File? = null,
) {
    companion object {
        const val DEFAULT_CONTEXT_LINES: Int = 10
        /** Cap when [line] is omitted so whole-file reads stay token-bounded. */
        const val DEFAULT_MAX_LINES: Int = 200
    }
}

data class ReadSourceResult(
    val gav: String,
    val path: String,
    val sourceRoot: String,
    val startLine: Int,
    val endLine: Int,
    val lineCount: Int,
    val snippet: String,
    val truncated: Boolean,
)

object DependencySourceReader {
    fun read(request: ReadSourceRequest): ReadSourceResult {
        request.artifact.validate()
        require(request.path.isNotBlank()) { "path must not be blank" }
        require(!request.path.contains('\u0000')) { "path must not contain NUL" }
        require(request.contextLines >= 0) { "contextLines must be non-negative" }
        require(request.maxLines >= 1) { "maxLines must be >= 1" }
        if (request.line != null) {
            require(request.line >= 1) { "line must be >= 1" }
        }

        val root = resolveSourceRoot(request)
            ?: throw IllegalArgumentException(
                "Sources not found for ${request.artifact.gav()}. " +
                    "Pass sourceRoot (required for Idea directory / sourcePaths keep-sets), " +
                    "or ensure a *-sources.jar exists under Maven local / Gradle caches " +
                    "(optional gradleUserHome).",
            )
        val normalizedPath = normalizeEntryPath(request.path)
        require(normalizedPath.isNotBlank()) { "path must not be blank" }
        require(SourcesJarCorpus.isSourceFile(normalizedPath.substringAfterLast('/'))) {
            "path must be a source file (.java/.kt/.kts): $normalizedPath"
        }

        val text =
            try {
                readText(root, normalizedPath)
            } catch (error: ZipException) {
                throw IllegalArgumentException(
                    "Failed to read sources jar ${root.absolutePath}: ${error.message}",
                    error,
                )
            } catch (error: IOException) {
                throw IllegalArgumentException(
                    "Failed to read source $normalizedPath from ${root.absolutePath}: ${error.message}",
                    error,
                )
            }
        val lines = text.split('\n')
        val totalLines = if (text.isEmpty()) 0 else lines.size

        if (request.line != null && totalLines > 0 && request.line > totalLines) {
            throw IllegalArgumentException(
                "line ${request.line} is past end of file ($totalLines lines)",
            )
        }

        val (startLine, endLine, truncated) = resolveRange(
            totalLines = totalLines,
            line = request.line,
            contextLines = request.contextLines,
            maxLines = request.maxLines,
        )
        val snippet =
            if (totalLines == 0 || startLine > endLine) {
                ""
            } else {
                lines.subList(startLine - 1, endLine).joinToString("\n")
            }

        return ReadSourceResult(
            gav = request.artifact.gav(),
            path = normalizedPath,
            sourceRoot = root.absolutePath,
            startLine = startLine,
            endLine = endLine,
            lineCount = totalLines,
            snippet = snippet,
            truncated = truncated,
        )
    }

    fun parseGav(gav: String): DependencyArtifactRef {
        require(gav.isNotBlank()) { "gav must not be blank" }
        val parts = gav.split(':')
        require(parts.size == 3) {
            "gav must be group:name:version (exactly three colon-separated parts)"
        }
        return DependencyArtifactRef(
            group = parts[0],
            name = parts[1],
            version = parts[2],
        ).also { it.validate() }
    }

    private fun resolveSourceRoot(request: ReadSourceRequest): File? {
        val explicit = request.sourceRoot
        if (explicit != null) {
            require(explicit.exists()) {
                "sourceRoot does not exist: ${explicit.absolutePath}"
            }
            return explicit
        }
        return LocalSourcesJarLocator.find(request.artifact, request.gradleUserHome)
    }

    private fun normalizeEntryPath(path: String): String =
        path.trim().trimStart('/').replace('\\', '/')

    private fun readText(root: File, path: String): String =
        when {
            root.isDirectory -> readFromDirectory(root, path)
            root.isFile && (root.name.endsWith(".jar", ignoreCase = true) ||
                root.name.endsWith(".zip", ignoreCase = true)) -> readFromZip(root, path)
            root.isFile && SourcesJarCorpus.isSourceFile(root.name) -> {
                require(path == root.name || path.endsWith("/${root.name}")) {
                    "path '$path' does not match single-file sourceRoot '${root.name}'"
                }
                root.readText(Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException(
                "sourceRoot must be a sources jar/zip, source directory, or source file: ${root.absolutePath}",
            )
        }

    private fun readFromDirectory(root: File, path: String): String {
        val target = File(root, path).canonicalFile
        val rootCanonical = root.canonicalFile
        require(
            target.absolutePath.startsWith(rootCanonical.absolutePath + File.separator) ||
                target == rootCanonical,
        ) {
            "path escapes sourceRoot: $path"
        }
        require(target.isFile) { "Source file not found under sourceRoot: $path" }
        return target.readText(Charsets.UTF_8)
    }

    private fun readFromZip(root: File, path: String): String {
        ZipFile(root).use { zip ->
            val entry = zip.getEntry(path)
                ?: zip.getEntry("/$path")
                ?: throw IllegalArgumentException("Entry not found in sources jar: $path")
            require(!entry.isDirectory) { "Entry is a directory, not a source file: $path" }
            return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun resolveRange(
        totalLines: Int,
        line: Int?,
        contextLines: Int,
        maxLines: Int,
    ): Triple<Int, Int, Boolean> {
        if (totalLines == 0) {
            return Triple(0, 0, false)
        }
        if (line == null) {
            val end = minOf(totalLines, maxLines)
            return Triple(1, end, end < totalLines)
        }
        val start = maxOf(1, line - contextLines)
        val end = minOf(totalLines, line + contextLines)
        val truncated = start > 1 || end < totalLines
        return Triple(start, end, truncated)
    }
}
