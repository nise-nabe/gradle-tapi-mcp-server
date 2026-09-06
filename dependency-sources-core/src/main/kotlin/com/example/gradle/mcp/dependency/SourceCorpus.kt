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

    fun load(member: KeepSetMember): List<SourceDocument> {
        val root = member.sourceRoot
        return when {
            root.isDirectory -> SourceTreeCorpus.load(member)
            root.isFile && (root.extensionEquals("jar") || root.extensionEquals("zip")) -> loadZip(member)
            root.isFile && isSourceFile(root.name) ->
                listOf(
                    SourceDocument(
                        gav = member.gav,
                        path = root.name,
                        text = root.readText(Charsets.UTF_8),
                    ),
                )
            else -> emptyList()
        }
    }

    private fun loadZip(member: KeepSetMember): List<SourceDocument> {
        val docs = ArrayList<SourceDocument>()
        ZipFile(member.sourceRoot).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !isSourceFile(entry.name)) {
                    continue
                }
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                docs.add(
                    SourceDocument(
                        gav = member.gav,
                        path = entry.name.trimStart('/'),
                        text = text,
                    ),
                )
            }
        }
        return docs
    }

    private fun File.extensionEquals(ext: String): Boolean =
        name.endsWith(".$ext", ignoreCase = true)
}

object SourceTreeCorpus {
    fun load(member: KeepSetMember): List<SourceDocument> {
        val root = member.sourceRoot
        if (!root.isDirectory) {
            return emptyList()
        }
        val rootPath = root.toPath()
        return root.walkTopDown()
            .filter { it.isFile && SourcesJarCorpus.isSourceFile(it.name) }
            .map { file ->
                val relative = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
                SourceDocument(
                    gav = member.gav,
                    path = relative,
                    text = file.readText(Charsets.UTF_8),
                )
            }
            .toList()
    }
}