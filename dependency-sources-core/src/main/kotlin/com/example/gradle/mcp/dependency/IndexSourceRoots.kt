package com.example.gradle.mcp.dependency

import java.io.File
import java.util.zip.ZipFile

/**
 * Side-car mapping of GAV → source roots written next to an index.
 * Does not bump [IndexFormat.VERSION]; older indexes simply lack the file.
 */
sealed interface SourceRootResolution {
    data class Found(val root: File) : SourceRootResolution
    data object Missing : SourceRootResolution
    data object Ambiguous : SourceRootResolution
}

object IndexSourceRoots {
    const val FILE_NAME: String = "source-roots.tsv"

    fun write(directory: File, members: List<KeepSetMember>) {
        val lines = members
            .asSequence()
            .map { it.gav to it.sourceRoot.absolutePath }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (gav, root) -> "${escape(gav)}\t${escape(root)}" }
            .toList()
        val target = File(directory, FILE_NAME)
        val tmp = File(directory, "$FILE_NAME.tmp-${System.nanoTime()}")
        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(directory: File): Map<String, List<File>> {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return emptyMap()
        val result = LinkedHashMap<String, ArrayList<File>>()
        file.forEachLine { raw ->
            if (raw.isBlank()) return@forEachLine
            val tab = raw.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val gav = unescape(raw.substring(0, tab))
            val root = File(unescape(raw.substring(tab + 1)))
            result.getOrPut(gav) { ArrayList() }.add(root)
        }
        return result
    }

    /**
     * Return a root that contains [path], or null.
     *
     * [jarEntriesCache] maps absolute jar/zip path → entry names (without leading `/`).
     * Callers enriching many hits should reuse one cache so each jar is opened once.
     */
    fun resolve(
        rootsByGav: Map<String, List<File>>,
        gav: String,
        path: String,
        jarEntriesCache: MutableMap<String, Set<String>>? = null,
    ): SourceRootResolution {
        val roots = rootsByGav[gav].orEmpty().filter { it.exists() }
        if (roots.isEmpty()) return SourceRootResolution.Missing
        val normalized = normalizeRelativePath(path) ?: return SourceRootResolution.Missing
        var match: File? = null
        for (root in roots) {
            if (!containsPath(root, normalized, jarEntriesCache)) continue
            if (match != null) {
                // Multiple roots contain the same relative path — do not guess.
                return SourceRootResolution.Ambiguous
            }
            match = root
        }
        return match?.let { SourceRootResolution.Found(it) } ?: SourceRootResolution.Missing
    }


    internal fun normalizeRelativePath(path: String): String? {
        val normalized = path.trim().trimStart('/').replace('\\', '/')
        if (normalized.isEmpty()) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return normalized
    }

    internal fun containsPath(
        root: File,
        path: String,
        jarEntriesCache: MutableMap<String, Set<String>>? = null,
    ): Boolean =
        when {
            root.isDirectory -> File(root, path).isFile
            root.isFile && (root.name.endsWith(".jar", ignoreCase = true) ||
                root.name.endsWith(".zip", ignoreCase = true)) -> {
                val entries =
                    if (jarEntriesCache != null) {
                        jarEntriesCache.getOrPut(root.absolutePath) { loadJarEntries(root) }
                    } else {
                        loadJarEntries(root)
                    }
                path in entries || "/$path" in entries
            }
            root.isFile && SourcesJarCorpus.isSourceFile(root.name) ->
                path == root.name || path.endsWith("/${root.name}")
            else -> false
        }

    private fun loadJarEntries(root: File): Set<String> =
        runCatching {
            ZipFile(root).use { zip ->
                zip.entries().asSequence().map { it.name }.toHashSet()
            }
        }.getOrDefault(emptySet())

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> {
                        out.append(ch)
                        i += 1
                        continue
                    }
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }
}
