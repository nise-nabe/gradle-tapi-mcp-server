package com.example.gradle.mcp.dependency

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

        val loaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL)!!
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
        manifest.writeText(manifest.readText().replace("\"formatVersion\":1", "\"formatVersion\":99"))
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }
}