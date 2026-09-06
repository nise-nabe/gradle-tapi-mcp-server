package com.example.gradle.mcp.dependency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class GapEliasDeltaCodecTest {
    @Test
    fun `round-trips strictly increasing positions`() {
        val positions = intArrayOf(0, 1, 5, 20, 21, 100)
        val encoded = GapEliasDeltaCodec.encode(positions)
        GapEliasDeltaCodec.decode(encoded, positions.size).toList() shouldContainExactly positions.toList()
    }

    @Test
    fun `empty posting encodes to empty bytes`() {
        val encoded = GapEliasDeltaCodec.encode(intArrayOf())
        encoded shouldHaveSize 0
        GapEliasDeltaCodec.decode(encoded, 0) shouldHaveSize 0
    }

    @Test
    fun `round-trips occurrence payloads with same-doc and doc-change`() {
        val occs =
            listOf(
                OccPos(docId = 0, line = 1, column = 0),
                OccPos(docId = 0, line = 3, column = 5),
                OccPos(docId = 2, line = 0, column = 1),
                OccPos(docId = 2, line = 10, column = 0),
                OccPos(docId = 5, line = 7, column = 12),
            )
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size) shouldContainExactly occs
    }

    @Test
    fun `empty occurrence posting encodes to empty bytes`() {
        val encoded = GapEliasDeltaCodec.encodeOccurrences(emptyList())
        encoded shouldHaveSize 0
        GapEliasDeltaCodec.decodeOccurrences(encoded, 0) shouldHaveSize 0
    }

    @Test
    fun `rejects negative occurrence count`() {
        shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.decodeOccurrences(ByteArray(0), -1)
        }
    }

    @Test
    fun `rejects unsorted same-doc occurrences on encode`() {
        shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.encodeOccurrences(
                listOf(
                    OccPos(docId = 0, line = 5, column = 0),
                    OccPos(docId = 0, line = 3, column = 0),
                ),
            )
        }
    }
}



    @Test
    fun `round-trips first docId at Int MAX_VALUE`() {
        val occs = listOf(OccPos(docId = Int.MAX_VALUE, line = 0, column = 0))
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size) shouldContainExactly occs
    }

class IdentifierLexerTest {
    @Test
    fun `idents skips comments and strings but all keeps them`() {
        val source =
            """
            // Foo in comment
            class Bar {
              val x = "Baz"
              fun Foo() {}
            }
            """.trimIndent()

        val idents = IdentifierLexer.tokenize(source, TokenMode.IDENTS).map { it.name }
        val all = IdentifierLexer.tokenize(source, TokenMode.ALL).map { it.name }

        idents.count { it == "Foo" } shouldBe 1
        all.count { it == "Foo" } shouldBe 2
        all.filter { it == "Baz" } shouldHaveSize 1
        idents.filter { it == "Baz" } shouldHaveSize 0
        idents.filter { it == "Bar" } shouldHaveSize 1
        all shouldContainExactly listOf("Foo", "in", "comment", "class", "Bar", "val", "x", "Baz", "fun", "Foo")
        idents shouldContainExactly listOf("class", "Bar", "val", "x", "fun", "Foo")
    }
}

class NameLocateIndexTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `indexes source tree and persists with format version`() {
        val sources = File(tempDir, "sources").apply { mkdirs() }
        File(sources, "Demo.java").writeText(
            """
            // HttpClient helper
            public class Demo {
              void run(HttpClient client) {}
            }
            """.trimIndent(),
        )
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "index")
        index.writeTo(indexDir)

        val hitsAll = index.locate("HttpClient")
        hitsAll.map { it.line }.sorted() shouldContainExactly listOf(1, 3)

        val loaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL).shouldNotBeNull()
        loaded.stats(indexDir, cacheHit = true).formatVersion shouldBe IndexFormat.VERSION
        val loadedHits = loaded.locate("HttpClient")
        loadedHits.map { it.line }.sorted() shouldContainExactly listOf(1, 3)
        loadedHits.forEach { hit ->
            hit.gav shouldBe "demo:lib:1"
            hit.path shouldBe "Demo.java"
        }
        loadedHits.map { it.column }.all { it >= 0 } shouldBe true

        val idents = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        idents.locate("HttpClient").map { it.line } shouldContainExactly listOf(3)
    }

    @Test
    fun `rejects incompatible format version`() {
        val sources = File(tempDir, "src").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx")
        index.writeTo(indexDir)
        val manifest = File(indexDir, NameLocateIndex.MANIFEST_NAME)
        manifest.writeText(manifest.readText().replace("\"formatVersion\":${IndexFormat.VERSION}", "\"formatVersion\":99"))
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects v1 formatVersion`() {
        val sources = File(tempDir, "src-v1").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-v1")
        index.writeTo(indexDir)
        val manifest = File(indexDir, NameLocateIndex.MANIFEST_NAME)
        manifest.writeText(manifest.readText().replace("\"formatVersion\":${IndexFormat.VERSION}", "\"formatVersion\":1"))
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `documents bin contains docs only`() {
        val sources = File(tempDir, "src-docs").apply { mkdirs() }
        File(sources, "Foo.java").writeText("class Foo {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-docs")
        index.writeTo(indexDir)

        DataInputStream(File(indexDir, NameLocateIndex.DOCUMENTS_NAME).inputStream().buffered()).use { input ->
            input.readInt() shouldBe IndexFormat.MAGIC
            input.readInt() shouldBe IndexFormat.VERSION
            input.readInt() shouldBe 1
            val gavLen = input.readInt()
            val gav = ByteArray(gavLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
            gav shouldBe "g:a:1"
            val pathLen = input.readInt()
            val path = ByteArray(pathLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
            path shouldBe "Foo.java"
            input.read() shouldBe -1
        }
    }

    @Test
    fun `rejects postings with out-of-range docId`() {
        val sources = File(tempDir, "src-oob").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-oob")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        // Preserve per-name occurrence counts and only replace the first posting blob so
        // load failure is attributable to docId range validation, not count mismatch.
        data class Posting(val count: Int, val blob: ByteArray)
        val (magic, version, postings) =
            DataInputStream(postingsFile.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                val nameCount = input.readInt()
                val entries = List(nameCount) {
                    val count = input.readInt()
                    val blob = ByteArray(input.readInt()).also { input.readFully(it) }
                    Posting(count, blob)
                }
                Triple(magic, version, entries)
            }
        magic shouldBe IndexFormat.MAGIC
        version shouldBe IndexFormat.VERSION
        require(postings.isNotEmpty())
        val targetCount = postings.first().count
        require(targetCount >= 1) { "expected at least one occurrence to forge" }
        val forgedSameCount =
            GapEliasDeltaCodec.encodeOccurrences(
                List(targetCount) { OccPos(docId = 99, line = it, column = 0) },
            )
        DataOutputStream(postingsFile.outputStream().buffered()).use { out ->
            out.writeInt(IndexFormat.MAGIC)
            out.writeInt(IndexFormat.VERSION)
            out.writeInt(postings.size)
            postings.forEachIndexed { index, posting ->
                val blob = if (index == 0) forgedSameCount else posting.blob
                out.writeInt(posting.count)
                out.writeInt(blob.size)
                out.write(blob)
            }
        }
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

}
