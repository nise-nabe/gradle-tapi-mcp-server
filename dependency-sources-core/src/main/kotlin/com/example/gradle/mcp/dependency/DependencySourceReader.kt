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
        /** Soft cap per source line to bound memory on minified/generated files. */
        const val MAX_LINE_CHARS: Int = 16_384
        /** Soft cap for returned snippet size (UTF-16 code units). */
        const val MAX_SNIPPET_CHARS: Int = 262_144
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

    private fun normalizeEntryPath(path: String): String {
        val normalized = path.trim().trimStart('/').replace('\\', '/')
        require(normalized.isNotBlank()) { "path must not be blank" }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "path must not contain empty or '..' segments: $path"
        }
        return normalized
    }

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
            var lineTruncated = false
            while (true) {
                val row = readBoundedLine(reader) ?: break
                total += 1
                if (row.truncated) lineTruncated = true
                if (kept.size < maxLines) {
                    kept.add(row.text)
                }
            }
            if (total == 0) {
                return ExtractedSnippet(0, 0, 0, "", truncated = false)
            }
            val endLine = minOf(total, maxLines)
            val snippet = joinBounded(kept)
            return ExtractedSnippet(
                startLine = 1,
                endLine = endLine,
                lineCount = total,
                snippet = snippet.text,
                truncated = endLine < total || lineTruncated || snippet.truncated,
            )
        }

        val windowStart = (line.toLong() - contextLines.toLong()).coerceAtLeast(1L).toInt()
        val windowEnd = (line.toLong() + contextLines.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val kept = ArrayList<String>(contextLines * 2 + 1)
        var total = 0
        var lineTruncated = false
        while (true) {
            val row = readBoundedLine(reader) ?: break
            total += 1
            if (row.truncated) lineTruncated = true
            if (total in windowStart..windowEnd) {
                kept.add(row.text)
            }
        }
        if (total == 0) {
            return ExtractedSnippet(0, 0, 0, "", truncated = false)
        }
        if (line > total) {
            return ExtractedSnippet(0, 0, total, "", truncated = false)
        }
        val endLine = minOf(total, windowEnd)
        val snippet = joinBounded(kept)
        return ExtractedSnippet(
            startLine = windowStart,
            endLine = endLine,
            lineCount = total,
            snippet = snippet.text,
            truncated = windowStart > 1 || endLine < total || lineTruncated || snippet.truncated,
        )
    }

    private data class BoundedLine(val text: String, val truncated: Boolean)

    private fun readBoundedLine(reader: BufferedReader): BoundedLine? {
        val max = ReadSourceRequest.MAX_LINE_CHARS
        val sb = StringBuilder()
        var truncated = false
        while (true) {
            val ch = reader.read()
            if (ch < 0) {
                return if (sb.isEmpty()) null else BoundedLine(sb.toString(), truncated)
            }
            if (ch == '\n'.code) {
                break
            }
            if (ch == '\r'.code) {
                reader.mark(1)
                val next = reader.read()
                if (next != '\n'.code && next >= 0) {
                    reader.reset()
                }
                break
            }
            if (sb.length < max) {
                sb.append(ch.toChar())
            } else {
                truncated = true
            }
        }
        return BoundedLine(sb.toString(), truncated)
    }

    private fun joinBounded(lines: List<String>): BoundedLine {
        if (lines.isEmpty()) return BoundedLine("", truncated = false)
        val max = ReadSourceRequest.MAX_SNIPPET_CHARS
        val sb = StringBuilder()
        var truncated = false
        for ((index, row) in lines.withIndex()) {
            if (index > 0) {
                if (sb.length >= max) {
                    truncated = true
                    break
                }
                sb.append('\n')
            }
            val remaining = max - sb.length
            if (remaining <= 0) {
                truncated = true
                break
            }
            if (row.length <= remaining) {
                sb.append(row)
            } else {
                sb.append(row, 0, remaining)
                truncated = true
                break
            }
        }
        return BoundedLine(sb.toString(), truncated)
    }
}
