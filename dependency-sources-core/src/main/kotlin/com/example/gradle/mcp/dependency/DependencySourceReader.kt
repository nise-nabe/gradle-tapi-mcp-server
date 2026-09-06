package com.example.gradle.mcp.dependency

import java.io.BufferedReader
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
        const val MAX_CONTEXT_LINES: Int = 100
        const val MAX_MAX_LINES: Int = 2_000
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
        require(request.contextLines <= ReadSourceRequest.MAX_CONTEXT_LINES) {
            "contextLines must be <= ${ReadSourceRequest.MAX_CONTEXT_LINES}"
        }
        require(request.maxLines >= 1) { "maxLines must be >= 1" }
        require(request.maxLines <= ReadSourceRequest.MAX_MAX_LINES) {
            "maxLines must be <= ${ReadSourceRequest.MAX_MAX_LINES}"
        }
        if (request.line != null) {
            require(request.line >= 1) { "line must be >= 1" }
        }

        val root = resolveSourceRoot(request)
            ?: throw IllegalArgumentException(
                "Sources not found for ${request.artifact.gav()}. " +
                    "Pass sourceRoot (required for Idea directory / sourcePaths keep-sets when " +
                    "the index has no source-roots.tsv), or ensure a *-sources.jar exists under " +
                    "Maven local / Gradle caches (optional gradleUserHome).",
            )
        val normalizedPath = normalizeEntryPath(request.path)
        require(normalizedPath.isNotBlank()) { "path must not be blank" }
        require(SourcesJarCorpus.isSourceFile(normalizedPath.substringAfterLast('/'))) {
            "path must be a source file (.java/.kt/.kts): $normalizedPath"
        }

        val extracted =
            try {
                extractSnippet(
                    root = root,
                    path = normalizedPath,
                    line = request.line,
                    contextLines = request.contextLines,
                    maxLines = request.maxLines,
                )
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

        if (request.line != null && request.line > extracted.lineCount) {
            throw IllegalArgumentException(
                "line ${request.line} is past end of file (${extracted.lineCount} lines)",
            )
        }

        return ReadSourceResult(
            gav = request.artifact.gav(),
            path = normalizedPath,
            sourceRoot = root.absolutePath,
            startLine = extracted.startLine,
            endLine = extracted.endLine,
            lineCount = extracted.lineCount,
            snippet = extracted.snippet,
            truncated = extracted.truncated,
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

    private data class ExtractedSnippet(
        val startLine: Int,
        val endLine: Int,
        val lineCount: Int,
        val snippet: String,
        val truncated: Boolean,
    )

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

    private fun extractSnippet(
        root: File,
        path: String,
        line: Int?,
        contextLines: Int,
        maxLines: Int,
    ): ExtractedSnippet =
        openReader(root, path).use { reader ->
            windowedRead(reader, line = line, contextLines = contextLines, maxLines = maxLines)
        }

    private fun openReader(root: File, path: String): BufferedReader {
        when {
            root.isDirectory -> {
                val target = File(root, path).canonicalFile
                val rootCanonical = root.canonicalFile
                require(
                    target.absolutePath.startsWith(rootCanonical.absolutePath + File.separator) ||
                        target == rootCanonical,
                ) {
                    "path escapes sourceRoot: $path"
                }
                require(target.isFile) { "Source file not found under sourceRoot: $path" }
                return target.bufferedReader(Charsets.UTF_8)
            }
            root.isFile && (root.name.endsWith(".jar", ignoreCase = true) ||
                root.name.endsWith(".zip", ignoreCase = true)) -> {
                val zip = ZipFile(root)
                try {
                    val entry = zip.getEntry(path)
                        ?: zip.getEntry("/$path")
                        ?: throw IllegalArgumentException("Entry not found in sources jar: $path")
                    require(!entry.isDirectory) { "Entry is a directory, not a source file: $path" }
                    val stream = zip.getInputStream(entry)
                    return object : BufferedReader(stream.bufferedReader(Charsets.UTF_8)) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                zip.close()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    zip.close()
                    throw error
                }
            }
            root.isFile && SourcesJarCorpus.isSourceFile(root.name) -> {
                require(path == root.name || path.endsWith("/${root.name}")) {
                    "path '$path' does not match single-file sourceRoot '${root.name}'"
                }
                return root.bufferedReader(Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException(
                "sourceRoot must be a sources jar/zip, source directory, or source file: ${root.absolutePath}",
            )
        }
    }

    private fun windowedRead(
        reader: BufferedReader,
        line: Int?,
        contextLines: Int,
        maxLines: Int,
    ): ExtractedSnippet {
        if (line == null) {
            val kept = ArrayList<String>(minOf(maxLines, 256))
            var total = 0
            while (true) {
                val row = reader.readLine() ?: break
                total += 1
                if (kept.size < maxLines) {
                    kept.add(row)
                }
            }
            if (total == 0) {
                return ExtractedSnippet(0, 0, 0, "", truncated = false)
            }
            val end = minOf(total, maxLines)
            return ExtractedSnippet(
                startLine = 1,
                endLine = end,
                lineCount = total,
                snippet = kept.joinToString("\n"),
                truncated = end < total,
            )
        }

        val windowStart = (line.toLong() - contextLines.toLong()).coerceAtLeast(1L).toInt()
        val windowEnd = (line.toLong() + contextLines.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val kept = ArrayList<String>(contextLines * 2 + 1)
        var total = 0
        while (true) {
            val row = reader.readLine() ?: break
            total += 1
            if (total in windowStart..windowEnd) {
                kept.add(row)
            }
        }
        if (total == 0) {
            return ExtractedSnippet(0, 0, 0, "", truncated = false)
        }
        if (line > total) {
            return ExtractedSnippet(0, 0, total, "", truncated = false)
        }
        val end = minOf(total, windowEnd)
        return ExtractedSnippet(
            startLine = windowStart,
            endLine = end,
            lineCount = total,
            snippet = kept.joinToString("\n"),
            truncated = windowStart > 1 || end < total,
        )
    }
}
