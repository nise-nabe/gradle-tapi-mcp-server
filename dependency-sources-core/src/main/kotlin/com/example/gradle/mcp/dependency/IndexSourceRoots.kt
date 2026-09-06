package com.example.gradle.mcp.dependency

import java.io.File
import java.util.zip.ZipFile

/**
 * Side-car mapping of GAV → source roots written next to an index.
 * Does not bump [IndexFormat.VERSION]; older indexes simply lack the file.
 */
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
        File(directory, FILE_NAME).writeText(
            if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"),
        )
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
     * Return a root that contains [path]. Never returns a root that fails the containment
     * check (so callers can fall back to cache jar lookup).
     *
     * [containsCache] memoizes jar/dir probes across many hits in one search.
     */
    fun resolve(
        rootsByGav: Map<String, List<File>>,
        gav: String,
        path: String,
        containsCache: MutableMap<String, Boolean>? = null,
    ): File? {
        val roots = rootsByGav[gav].orEmpty().filter { it.exists() }
        if (roots.isEmpty()) return null
        val normalized = path.trim().trimStart('/').replace('\\', '/')
        for (root in roots) {
            val cacheKey = root.absolutePath + '\u0000' + normalized
            val contains =
                if (containsCache != null) {
                    containsCache.getOrPut(cacheKey) { containsPath(root, normalized) }
                } else {
                    containsPath(root, normalized)
                }
            if (contains) return root
        }
        return null
    }

    internal fun containsPath(root: File, path: String): Boolean =
        when {
            root.isDirectory -> File(root, path).isFile
            root.isFile && (root.name.endsWith(".jar", ignoreCase = true) ||
                root.name.endsWith(".zip", ignoreCase = true)) -> {
                runCatching {
                    ZipFile(root).use { zip ->
                        zip.getEntry(path) != null || zip.getEntry("/$path") != null
                    }
                }.getOrDefault(false)
            }
            root.isFile && SourcesJarCorpus.isSourceFile(root.name) ->
                path == root.name || path.endsWith("/${root.name}")
            else -> false
        }

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
