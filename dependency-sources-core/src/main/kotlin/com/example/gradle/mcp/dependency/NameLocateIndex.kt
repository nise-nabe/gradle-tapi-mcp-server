package com.example.gradle.mcp.dependency

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

object IndexFormat {
    /** v2: occurrence payloads live in postings.bin; documents.bin is docs-only. */
    const val VERSION: Int = 2
    const val MAGIC: Int = 0x4D445349 // MDSI
}

data class IndexManifest(
    val formatVersion: Int,
    val tokenMode: String,
    val fingerprint: String,
    val keepSetMode: String,
    val docCount: Int,
    val nameCount: Int,
    val occurrenceCount: Int,
    val builtAtEpochMs: Long,
)

data class LocateHit(
    val gav: String,
    val path: String,
    val line: Int,
    val column: Int,
    val matchedQueries: List<String> = emptyList(),
) {
    internal fun locateKey(): LocateKey = LocateKey(gav, path, line, column)
}

internal data class LocateKey(
    val gav: String,
    val path: String,
    val line: Int,
    val column: Int,
)

data class IndexStats(
    val formatVersion: Int,
    val tokenMode: TokenMode,
    val fingerprint: String,
    val keepSetMode: String,
    val docCount: Int,
    val nameCount: Int,
    val occurrenceCount: Int,
    val indexDir: File,
    val cacheHit: Boolean,
)

private data class DocMeta(val gav: String, val path: String)

class NameLocateIndex private constructor(
    val tokenMode: TokenMode,
    val fingerprint: String,
    val keepSetMode: String,
    private val dictionary: NameDictionary,
    private val documents: List<DocMeta>,
    private val postings: List<Pair<ByteArray, Int>>,
    private val occurrenceCount: Int,
) {
    fun stats(indexDir: File, cacheHit: Boolean): IndexStats =
        IndexStats(
            formatVersion = IndexFormat.VERSION,
            tokenMode = tokenMode,
            fingerprint = fingerprint,
            keepSetMode = keepSetMode,
            docCount = documents.size,
            nameCount = dictionary.size(),
            occurrenceCount = occurrenceCount,
            indexDir = indexDir,
            cacheHit = cacheHit,
        )

    /**
     * Exact simple-name locate. [limit] null = unlimited; 0 = empty without decode work.
     * Returns hits in posting order (doc_id, line, col). Caps returned hit count, not decoded rows.
     */
    fun locate(query: String, limit: Int? = null): List<LocateHit> {
        if (limit == 0) return emptyList()
        val nameId = dictionary.lookup(query) ?: return emptyList()
        val (blob, count) = postings.getOrNull(nameId) ?: return emptyList()
        if (limit == null) {
            val hits = ArrayList<LocateHit>(count)
            GapEliasDeltaCodec.forEachOccurrence(blob, count) { docId, line, column ->
                val doc = documents[docId]
                hits.add(LocateHit(doc.gav, doc.path, line, column))
                true
            }
            return hits
        }
        val hits = ArrayList<LocateHit>(minOf(limit, count))
        GapEliasDeltaCodec.forEachOccurrence(blob, count) { docId, line, column ->
            val doc = documents[docId]
            hits.add(LocateHit(doc.gav, doc.path, line, column))
            hits.size < limit
        }
        return hits
    }

    /**
     * Multi-query OR with dedup and [LocateHit.matchedQueries] tags.
     * Merged hits sort by (gav, path, line, column); overall [limit] truncates after merge.
     */
    fun searchMulti(
        queries: List<String>,
        limit: Int? = null,
        perQueryLimit: Int? = null,
    ): List<LocateHit> {
        if (limit == 0) return emptyList()
        val merged = LinkedHashMap<LocateKey, LocateHit>()
        for (query in queries) {
            for (hit in locate(query, perQueryLimit)) {
                val key = hit.locateKey()
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = hit.copy(matchedQueries = listOf(query))
                } else if (!existing.matchedQueries.contains(query)) {
                    merged[key] = existing.copy(matchedQueries = existing.matchedQueries + query)
                }
            }
        }
        val sorted = merged.values.sortedWith(compareBy({ it.gav }, { it.path }, { it.line }, { it.column }))
        return if (limit == null) sorted else sorted.take(limit)
    }

    fun writeTo(directory: File) {
        directory.parentFile?.mkdirs()
        val tmp = File(directory.parentFile, "${directory.name}.tmp-${System.nanoTime()}")
        tmp.mkdirs()
        try {
            File(tmp, MANIFEST_NAME).writeText(
                ManifestJson.encode(
                    IndexManifest(
                        formatVersion = IndexFormat.VERSION,
                        tokenMode = tokenMode.wireName(),
                        fingerprint = fingerprint,
                        keepSetMode = keepSetMode,
                        docCount = documents.size,
                        nameCount = dictionary.size(),
                        occurrenceCount = occurrenceCount,
                        builtAtEpochMs = System.currentTimeMillis(),
                    ),
                ),
            )
            writeDictionary(tmp)
            writeDocuments(tmp)
            writePostings(tmp)
            // Replace without a bare delete→move gap: move the live dir aside first so
            // concurrent readers still see a complete tree until the swap completes.
            if (directory.exists()) {
                val backup = File(directory.parentFile, "${directory.name}.old-${System.nanoTime()}")
                Files.move(directory.toPath(), backup.toPath())
                try {
                    Files.move(tmp.toPath(), directory.toPath())
                    backup.deleteRecursively()
                } catch (error: Exception) {
                    if (!directory.exists() && backup.exists()) {
                        runCatching { Files.move(backup.toPath(), directory.toPath()) }
                    }
                    throw error
                }
            } else {
                Files.move(tmp.toPath(), directory.toPath())
            }
        } catch (error: Exception) {
            tmp.deleteRecursively()
            throw error
        }
    }

    private fun writeDictionary(directory: File) {
        DataOutputStream(File(directory, DICTIONARY_NAME).outputStream().buffered()).use { out ->
            out.writeInt(IndexFormat.MAGIC)
            out.writeInt(IndexFormat.VERSION)
            val names = dictionary.names()
            out.writeInt(names.size)
            for (name in names) writeUtf(out, name)
        }
    }

    private fun writeDocuments(directory: File) {
        DataOutputStream(File(directory, DOCUMENTS_NAME).outputStream().buffered()).use { out ->
            out.writeInt(IndexFormat.MAGIC)
            out.writeInt(IndexFormat.VERSION)
            out.writeInt(documents.size)
            for (doc in documents) {
                writeUtf(out, doc.gav)
                writeUtf(out, doc.path)
            }
        }
    }

    private fun writePostings(directory: File) {
        DataOutputStream(File(directory, POSTINGS_NAME).outputStream().buffered()).use { out ->
            out.writeInt(IndexFormat.MAGIC)
            out.writeInt(IndexFormat.VERSION)
            out.writeInt(postings.size)
            for ((blob, count) in postings) {
                out.writeInt(count)
                out.writeInt(blob.size)
                out.write(blob)
            }
        }
    }

    companion object {
        const val MANIFEST_NAME: String = "manifest.json"
        const val DICTIONARY_NAME: String = "dictionary.bin"
        const val DOCUMENTS_NAME: String = "documents.bin"
        const val POSTINGS_NAME: String = "postings.bin"

        fun build(
            members: List<KeepSetMember>,
            tokenMode: TokenMode,
            fingerprint: String,
            keepSetMode: String,
        ): NameLocateIndex {
            val dictionary = NameDictionary()
            val documents = ArrayList<DocMeta>()
            val postingBuilder = ArrayList<ArrayList<OccPos>>()

            fun ensurePosting(nameId: Int): ArrayList<OccPos> {
                while (postingBuilder.size <= nameId) postingBuilder.add(ArrayList())
                return postingBuilder[nameId]
            }

            for (member in members) {
                for (doc in SourcesJarCorpus.load(member)) {
                    val docId = documents.size
                    documents.add(DocMeta(doc.gav, doc.path))
                    for (token in IdentifierLexer.tokenize(doc.text, tokenMode)) {
                        val nameId = dictionary.intern(token.name)
                        ensurePosting(nameId).add(OccPos(docId, token.line, token.column))
                    }
                }
            }

            val postings = ArrayList<Pair<ByteArray, Int>>(dictionary.size())
            var totalOccurrencesLong = 0L
            for (nameId in 0 until dictionary.size()) {
                // Docs are scanned in order and the lexer emits tokens in source order, so
                // each per-name list is already sorted by (docId, line, column).
                val list = if (nameId < postingBuilder.size) postingBuilder[nameId] else emptyList()
                val blob = GapEliasDeltaCodec.encodeOccurrences(list)
                postings.add(blob to list.size)
                totalOccurrencesLong += list.size.toLong()
            }
            require(totalOccurrencesLong in 0L..Int.MAX_VALUE.toLong()) {
                "occurrence count $totalOccurrencesLong does not fit in Int"
            }
            val totalOccurrences = totalOccurrencesLong.toInt()

            return NameLocateIndex(
                tokenMode = tokenMode,
                fingerprint = fingerprint,
                keepSetMode = keepSetMode,
                dictionary = dictionary,
                documents = documents,
                postings = postings,
                occurrenceCount = totalOccurrences,
            )
        }

        fun tryLoad(
            directory: File,
            expectedFingerprint: String?,
            expectedTokenMode: TokenMode?,
        ): NameLocateIndex? {
            if (!directory.isDirectory) return null
            val manifestFile = File(directory, MANIFEST_NAME)
            if (!manifestFile.isFile) return null
            val manifest = ManifestJson.decode(manifestFile.readText()) ?: return null
            if (manifest.formatVersion != IndexFormat.VERSION) return null
            if (expectedFingerprint != null && manifest.fingerprint != expectedFingerprint) return null
            val tokenMode = runCatching { TokenMode.parse(manifest.tokenMode) }.getOrNull() ?: return null
            if (expectedTokenMode != null && tokenMode != expectedTokenMode) return null
            return runCatching { readBins(directory, manifest, tokenMode) }.getOrNull()
        }

        private fun readBins(
            directory: File,
            manifest: IndexManifest,
            tokenMode: TokenMode,
        ): NameLocateIndex {
            val dictionary = readDictionary(File(directory, DICTIONARY_NAME))
            val documents = readDocuments(File(directory, DOCUMENTS_NAME))
            val postings = readPostings(File(directory, POSTINGS_NAME))
            require(dictionary.size() == manifest.nameCount)
            require(documents.size == manifest.docCount)
            require(postings.size == dictionary.size())
            val totalOccurrencesLong = postings.fold(0L) { acc, posting -> acc + posting.second.toLong() }
            require(totalOccurrencesLong in 0L..Int.MAX_VALUE.toLong()) {
                "occurrence count $totalOccurrencesLong does not fit in Int"
            }
            val totalOccurrences = totalOccurrencesLong.toInt()
            require(totalOccurrences == manifest.occurrenceCount)
            // Eagerly validate posting payloads so corrupt indexes fail at load, not at search.
            // Stream docIds without materializing full OccPos lists (keeps load GC bounded).
            for ((blob, count) in postings) {
                GapEliasDeltaCodec.validateOccurrences(blob, count, documents.size)
            }
            return NameLocateIndex(
                tokenMode = tokenMode,
                fingerprint = manifest.fingerprint,
                keepSetMode = manifest.keepSetMode,
                dictionary = dictionary,
                documents = documents,
                postings = postings,
                occurrenceCount = totalOccurrences,
            )
        }

        private fun readDictionary(file: File): NameDictionary {
            DataInputStream(file.inputStream().buffered()).use { input ->
                requireMagic(input)
                val count = input.readInt()
                val dictionary = NameDictionary()
                repeat(count) { dictionary.intern(readUtf(input)) }
                return dictionary
            }
        }

        private fun readDocuments(file: File): List<DocMeta> {
            DataInputStream(file.inputStream().buffered()).use { input ->
                requireMagic(input)
                val docCount = input.readInt()
                val documents = ArrayList<DocMeta>(docCount)
                repeat(docCount) { documents.add(DocMeta(readUtf(input), readUtf(input))) }
                return documents
            }
        }

        private fun readPostings(file: File): List<Pair<ByteArray, Int>> {
            DataInputStream(file.inputStream().buffered()).use { input ->
                requireMagic(input)
                val count = input.readInt()
                require(count >= 0) { "posting entry count must be non-negative" }
                // Header is MAGIC+VERSION+count (12 bytes). Each entry needs at least
                // occCount+blobSize (8 bytes), so reject impossible counts before allocating.
                val maxEntriesByFile = ((file.length() - 12L) / 8L).coerceAtLeast(0L)
                require(count.toLong() <= maxEntriesByFile) {
                    "posting entry count $count exceeds file capacity (max $maxEntriesByFile for ${file.length()} bytes)"
                }
                val postings = ArrayList<Pair<ByteArray, Int>>(count)
                repeat(count) {
                    val occCount = input.readInt()
                    require(occCount >= 0) { "occurrence count must be non-negative" }
                    val blobSize = input.readInt()
                    require(blobSize >= 0) { "posting blob size must be non-negative" }
                    require(occCount > 0 || blobSize == 0) {
                        "empty occurrence count cannot have a non-empty posting blob"
                    }
                    require(blobSize.toLong() <= file.length()) {
                        "posting blob size $blobSize exceeds file length ${file.length()}"
                    }
                    val blob = ByteArray(blobSize)
                    input.readFully(blob)
                    postings.add(blob to occCount)
                }
                return postings
            }
        }

        private fun requireMagic(input: DataInputStream) {
            require(input.readInt() == IndexFormat.MAGIC)
            require(input.readInt() == IndexFormat.VERSION)
        }

        private fun writeUtf(out: DataOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readUtf(input: DataInputStream): String {
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

object KeepSetFingerprint {
    fun compute(tokenMode: TokenMode, keepSetMode: String, members: List<KeepSetMember>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(IndexFormat.VERSION.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(tokenMode.wireName().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(keepSetMode.toByteArray(Charsets.UTF_8))
        digest.update(0)
        val sorted = members.sortedWith(compareBy({ it.gav }, { it.fingerprintFile.absolutePath }))
        for (member in sorted) {
            digest.update(member.gav.toByteArray(Charsets.UTF_8))
            digest.update(0)
            appendFileFingerprint(digest, member.fingerprintFile)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun appendFileFingerprint(digest: MessageDigest, file: File) {
        if (!file.exists()) {
            digest.update("missing".toByteArray(Charsets.UTF_8))
            return
        }
        if (file.isFile) {
            digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.lastModified().toString().toByteArray(Charsets.UTF_8))
            return
        }
        digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
        digest.update(0)
        file.walkTopDown()
            .filter { it.isFile && SourcesJarCorpus.isSourceFile(it.name) }
            .sortedBy { it.absolutePath }
            .forEach { child ->
                digest.update(child.absolutePath.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(child.length().toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(child.lastModified().toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
    }
}

internal object ManifestJson {
    fun encode(manifest: IndexManifest): String =
        buildString {
            append('{')
            field("formatVersion", manifest.formatVersion)
            append(',')
            field("tokenMode", manifest.tokenMode)
            append(',')
            field("fingerprint", manifest.fingerprint)
            append(',')
            field("keepSetMode", manifest.keepSetMode)
            append(',')
            field("docCount", manifest.docCount)
            append(',')
            field("nameCount", manifest.nameCount)
            append(',')
            field("occurrenceCount", manifest.occurrenceCount)
            append(',')
            field("builtAtEpochMs", manifest.builtAtEpochMs)
            append('}')
        }

    fun decode(text: String): IndexManifest? {
        fun str(key: String): String? =
            "\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"".toRegex()
                .find(text)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")

        fun num(key: String): Long? =
            "\"$key\"\\s*:\\s*(-?\\d+)".toRegex().find(text)?.groupValues?.get(1)?.toLongOrNull()

        return IndexManifest(
            formatVersion = num("formatVersion")?.toInt() ?: return null,
            tokenMode = str("tokenMode") ?: return null,
            fingerprint = str("fingerprint") ?: return null,
            keepSetMode = str("keepSetMode") ?: return null,
            docCount = num("docCount")?.toInt() ?: return null,
            nameCount = num("nameCount")?.toInt() ?: return null,
            occurrenceCount = num("occurrenceCount")?.toInt() ?: return null,
            builtAtEpochMs = num("builtAtEpochMs") ?: return null,
        )
    }

    private fun StringBuilder.field(key: String, value: String) {
        append('"').append(key).append('"').append(':')
        append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    }

    private fun StringBuilder.field(key: String, value: Int) {
        append('"').append(key).append('"').append(':').append(value)
    }

    private fun StringBuilder.field(key: String, value: Long) {
        append('"').append(key).append('"').append(':').append(value)
    }
}
