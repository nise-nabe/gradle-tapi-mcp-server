package com.example.gradle.mcp.dependency

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
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
        loaded.locate("HttpClient").map { it.line }.sorted() shouldContainExactly listOf(1, 3)

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
        manifest.writeText(manifest.readText().replace("\"formatVersion\":2", "\"formatVersion\":99"))
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
        manifest.writeText(manifest.readText().replace("\"formatVersion\":2", "\"formatVersion\":1"))
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
            input.available() shouldBe 0
        }
    }
}