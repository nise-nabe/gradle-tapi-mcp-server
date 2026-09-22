package com.example.gradle.mcp.dependency

import java.io.File
import java.util.zip.ZipFile

data class KeepSetMember(
    val gav: String,
    val sourceRoot: File,
    val fingerprintFile: File = sourceRoot,
)

data class SourceDocument(
    val gav: String,
    val path: String,
    val text: String,
)

object SourcesJarCorpus {
    private val sourceExtensions = setOf("java", "kt", "kts")

    fun isSourceFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in sourceExtensions
    }

    fun load(member: KeepSetMember): List<SourceDocument> =
        buildList { forEachDocument(member, ::add) }

    fun forEachDocument(member: KeepSetMember, consume: (SourceDocument) -> Unit) {
        val root = member.sourceRoot
        when {
            root.isDirectory -> SourceTreeCorpus.forEachDocument(member, consume)
            root.isFile && (root.extensionEquals("jar") || root.extensionEquals("zip")) ->
                forEachZipEntry(member, consume)
            root.isFile && isSourceFile(root.name) ->
                consume(
                    SourceDocument(
                        gav = member.gav,
                        path = root.name,
                        text = root.readText(Charsets.UTF_8),
                    ),
                )
        }
    }

    private fun forEachZipEntry(member: KeepSetMember, consume: (SourceDocument) -> Unit) {
        ZipFile(member.sourceRoot).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !isSourceFile(entry.name)) {
                    continue
                }
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                consume(
                    SourceDocument(
                        gav = member.gav,
                        path = entry.name.trimStart('/'),
                        text = text,
                    ),
                )
            }
        }
    }

    private fun File.extensionEquals(ext: String): Boolean =
        name.endsWith(".$ext", ignoreCase = true)
}

object SourceTreeCorpus {
    fun load(member: KeepSetMember): List<SourceDocument> =
        buildList { forEachDocument(member, ::add) }

    fun forEachDocument(member: KeepSetMember, consume: (SourceDocument) -> Unit) {
        val root = member.sourceRoot
        if (!root.isDirectory) {
            return
        }
        val rootPath = root.toPath()
        root.walkTopDown()
            .filter { it.isFile && SourcesJarCorpus.isSourceFile(it.name) }
            .forEach { file ->
                val relative = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
                consume(
                    SourceDocument(
                        gav = member.gav,
                        path = relative,
                        text = file.readText(Charsets.UTF_8),
                    ),
                )
            }
    }
}