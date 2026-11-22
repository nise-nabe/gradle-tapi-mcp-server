package com.example.gradle.mcp.dependency

import java.io.File

/**
 * Canonical roots that client-supplied source paths must stay within.
 *
 * The MCP client chooses `sourcePaths`, `sourceRoot`, and (formerly) `indexDir`
 * arguments; without a boundary a caller could read source files or write an
 * index anywhere on the host. Roots are the connected project directory and
 * the Gradle user home reported by the Tooling API — both server-side values,
 * never client-supplied paths.
 */
object SourceAccessBoundary {
    fun roots(vararg candidates: File?): List<File> =
        candidates.filterNotNull()
            .map { it.canonicalFile }
            .distinctBy { it.toPath() }

    fun isWithin(file: File, roots: List<File>): Boolean {
        if (roots.isEmpty()) return false
        val candidate = file.canonicalFile.toPath()
        return roots.any { root ->
            // Canonicalize the root as well: callers may pass non-canonical
            // paths (e.g. 8.3 short names on Windows) that would otherwise
            // fail the prefix check against a canonical candidate.
            val rootPath = root.canonicalFile.toPath()
            candidate == rootPath || candidate.startsWith(rootPath)
        }
    }

    fun requireWithin(file: File, roots: List<File>, label: String) {
        if (!isWithin(file, roots)) {
            throw IllegalArgumentException(
                "$label is outside the allowed source boundary: ${file.absolutePath}. " +
                    "Use paths inside the project directory or the Gradle user home.",
            )
        }
    }
}
