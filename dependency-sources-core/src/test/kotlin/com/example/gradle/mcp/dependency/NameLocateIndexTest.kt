package com.example.gradle.mcp.dependency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun `decodeOccurrences respects limit`() {
        val occs =
            (0 until 200).map { i ->
                OccPos(docId = 0, line = i + 1, column = 0)
            }
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        val limited = GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size, limit = 5)
        val fullPrefix = GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size, limit = null).take(5)
        limited shouldContainExactly fullPrefix
    }

    @Test
    fun `decodeOccurrences limit zero validates first docId range`() {
        val occs = listOf(OccPos(docId = 5, line = 1, column = 0))
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        val error = shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.forEachOccurrence(
                bytes = encoded,
                count = occs.size,
                docCount = 1,
                limit = 0,
            ) { _, _, _ -> true }
        }
        error.message shouldContain "out of range"
    }

    @Test
    fun `decodeOccurrences limit zero returns empty for valid first entry`() {
        val occs = listOf(OccPos(docId = 0, line = 1, column = 0))
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size, limit = 0) shouldContainExactly emptyList()
    }

    @Test
    fun `forEachOccurrence rejects negative limit`() {
        val occs = listOf(OccPos(docId = 0, line = 1, column = 0))
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        val error = shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.forEachOccurrence(
                bytes = encoded,
                count = occs.size,
                docCount = 1,
                limit = -1,
            ) { _, _, _ -> true }
        }
        error.message shouldContain "non-negative"
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

    @Test
    fun `round-trips first docId at Int MAX_VALUE`() {
        val occs = listOf(OccPos(docId = Int.MAX_VALUE, line = 0, column = 0))
        val encoded = GapEliasDeltaCodec.encodeOccurrences(occs)
        GapEliasDeltaCodec.decodeOccurrences(encoded, occs.size) shouldContainExactly occs
    }

    @Test
    fun `decode rejects malformed delta with unary length 63`() {
        // 63 zero bits then a 1 (bit 63): lenL = 63 makes (1L shl 63) overflow to Long.MIN_VALUE
        val bytes = ByteArray(16).also { it[7] = 0x01 }
        shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.decode(bytes, 1)
        }
    }

    @Test
    fun `decode rejects malformed delta with length field 64`() {
        // lenL = 6 with rest = 0 gives len = 64, whose l = 63 would overflow (1L shl 63)
        val bytes = ByteArray(10).also { it[0] = 0x02 }
        shouldThrow<IllegalArgumentException> {
            GapEliasDeltaCodec.decode(bytes, 1)
        }
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

    @Test
    fun `idents skips triple-quoted raw strings including inner quotes`() {
        val triple = "\"\"\""
        val source =
            "class Bar {\n" +
                "  val msg = ${triple}He said \"hi\" loudly${triple}\n" +
                "  fun Foo() {}\n" +
                "}\n"

        val idents = IdentifierLexer.tokenize(source, TokenMode.IDENTS).map { it.name }

        idents.filter { it == "hi" } shouldHaveSize 0
        idents.filter { it == "said" } shouldHaveSize 0
        idents shouldContainExactly listOf("class", "Bar", "val", "msg", "fun", "Foo")
    }

    @Test
    fun `idents keeps line numbers across multiline raw strings`() {
        val triple = "\"\"\""
        val source =
            "val a = ${triple}line one\n" +
                "line two\n" +
                "line three${triple}\n" +
                "val After = 1\n"

        val occurrences = IdentifierLexer.tokenize(source, TokenMode.IDENTS)

        occurrences.map { it.name } shouldContainExactly listOf("val", "a", "val", "After")
        occurrences.first { it.name == "After" }.line shouldBe 4
    }

    @Test
    fun `idents treats unterminated triple quote as consuming the rest`() {
        val triple = "\"\"\""
        val source =
            "val a = ${triple}never closed\n" +
                "val Missing = 1\n"

        val idents = IdentifierLexer.tokenize(source, TokenMode.IDENTS).map { it.name }

        idents shouldContainExactly listOf("val", "a")
    }

    @Test
    fun `idents does not treat triple quotes inside comments as strings`() {
        val triple = "\"\"\""
        val source =
            "// $triple not a string opener\n" +
                "val After = 1\n"

        val idents = IdentifierLexer.tokenize(source, TokenMode.IDENTS).map { it.name }

        idents shouldContainExactly listOf("val", "After")
    }

    @Test
    fun `idents treats quadruple quote as raw string containing one quote`() {
        // """"x""" lexes as a raw string whose content is `"x` — no identifiers inside
        val source =
            "val a = " + "\"".repeat(4) + "x" + "\"".repeat(3) + "\n" +
                "val After = 1\n"

        val idents = IdentifierLexer.tokenize(source, TokenMode.IDENTS).map { it.name }

        idents shouldContainExactly listOf("val", "a", "val", "After")
    }
}

class NameLocateIndexTest {
    @TempDir
    lateinit var tempDir: File

    // mmap-backed loads lock postings.bin until closed (Windows tempDir teardown).
    private val openIndexes = mutableListOf<NameLocateIndex>()

    @AfterEach
    fun closeOpenIndexes() {
        openIndexes.forEach { it.close() }
        openIndexes.clear()
    }

    private fun NameLocateIndex?.trackForClose(): NameLocateIndex? =
        also { it?.let(openIndexes::add) }

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

        val loaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL).trackForClose().shouldNotBeNull()
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
    fun `v3 postings use offset table layout`() {
        val sources = File(tempDir, "v3-layout").apply { mkdirs() }
        File(sources, "Demo.java").writeText("class Demo { HttpClient c; }\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.IDENTS, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-v3")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        val bytes = postingsFile.readBytes()
        val table = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val nameCount = table.getInt(0)
        nameCount shouldBe index.stats(indexDir, cacheHit = false).nameCount
        var dataOffset = 0
        repeat(nameCount) { id ->
            val entryBase = 4 + id * PostingsTable.POSTING_ENTRY_SIZE
            table.getInt(entryBase) // count
            val offset = table.getInt(entryBase + 4)
            val len = table.getInt(entryBase + 8)
            offset shouldBe dataOffset
            dataOffset += len
        }
        postingsFile.length() shouldBe (4L + nameCount * PostingsTable.POSTING_ENTRY_SIZE + dataOffset)
    }

    @Test
    fun `mmap loaded index returns same hits as in-memory`() {
        val sources = File(tempDir, "mmap").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members)
        val built = NameLocateIndex.build(members, TokenMode.IDENTS, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-mmap")
        built.writeTo(indexDir)

        val loaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.IDENTS).trackForClose().shouldNotBeNull()
        built.locate("Foo", limit = null) shouldContainExactly loaded.locate("Foo", limit = null)
        loaded.locate("Foo", limit = 1) shouldHaveSize 1
    }

    @Test
    fun `locate limit zero returns empty without hits`() {
        val sources = File(tempDir, "limit-zero").apply { mkdirs() }
        File(sources, "Demo.java").writeText("class Demo { HttpClient c; }\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        index.locate("HttpClient", limit = 0) shouldContainExactly emptyList()
        index.locate("Missing", limit = 0) shouldContainExactly emptyList()
    }

    @Test
    fun `locate rejects negative limit`() {
        val sources = File(tempDir, "limit-neg").apply { mkdirs() }
        File(sources, "Demo.java").writeText("class Demo { HttpClient c; }\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val error = shouldThrow<IllegalArgumentException> {
            index.locate("HttpClient", limit = -1)
        }
        error.message shouldContain "non-negative"
    }

    @Test
    fun `postingCount reports occurrences without locate decode`() {
        val sources = File(tempDir, "posting-count").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        index.postingCount("Foo") shouldBe 2
        index.postingCount("Missing") shouldBe 0
    }

    @Test
    fun `locate limit one returns first posting-order hit`() {
        val sources = File(tempDir, "limit-one").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\nfun Foo() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val hits = index.locate("Foo", limit = 1)
        hits shouldHaveSize 1
        hits.single().line shouldBe 1
    }

    @Test
    fun `searchMulti dedups and tags matched queries`() {
        val sources = File(tempDir, "multi").apply { mkdirs() }
        File(sources, "A.java").writeText("class A { HttpClient a; Foo b; }\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val hits = index.searchMulti(
            queries = listOf("HttpClient", "Foo", "HttpClient"),
            limit = null,
            perQueryLimit = null,
        )
        hits shouldHaveSize 2
        val http = hits.single { it.matchedQueries.contains("HttpClient") }
        http.matchedQueries shouldContainExactly listOf("HttpClient")
    }

    @Test
    fun `searchMulti truncates after merge sort`() {
        val sources = File(tempDir, "multi-limit").apply { mkdirs() }
        File(sources, "B.java").writeText("class B { Zed z; Alpha a; }\n")
        val members = listOf(KeepSetMember(gav = "z:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val hits = index.searchMulti(
            queries = listOf("Zed", "Alpha"),
            limit = 1,
            perQueryLimit = null,
        )
        hits shouldHaveSize 1
        hits.single().path shouldBe "B.java"
        hits.single().line shouldBe 1
        hits.single().matchedQueries shouldContainExactly listOf("Zed")
    }

    @Test
    fun `searchMulti per query limit caps each query before merge`() {
        val sources = File(tempDir, "per-query").apply { mkdirs() }
        File(sources, "C.kt").writeText("fun Foo() {}\nfun Foo() {}\nfun Bar() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val hits = index.searchMulti(
            queries = listOf("Foo", "Bar"),
            limit = null,
            perQueryLimit = 1,
        )
        hits shouldHaveSize 2
        hits.count { it.matchedQueries == listOf("Foo") } shouldBe 1
        hits.single { it.matchedQueries == listOf("Bar") }.line shouldBe 3
    }

    @Test
    fun `searchMulti rejects negative limit`() {
        val sources = File(tempDir, "multi-neg").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        val limitError = shouldThrow<IllegalArgumentException> {
            index.searchMulti(queries = listOf("Foo"), limit = -1, perQueryLimit = null)
        }
        limitError.message shouldContain "non-negative"

        val perQueryError = shouldThrow<IllegalArgumentException> {
            index.searchMulti(queries = listOf("Foo"), limit = null, perQueryLimit = -2)
        }
        perQueryError.message shouldContain "perQueryLimit"
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
        shouldThrow<UnsupportedIndexFormatException> {
            NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL)
        }.message shouldContain "unsupported index format version 99"
    }

    @Test
    fun `rejects v2 formatVersion`() {
        val sources = File(tempDir, "src-v2").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-v2")
        index.writeTo(indexDir)
        val manifest = File(indexDir, NameLocateIndex.MANIFEST_NAME)
        manifest.writeText(manifest.readText().replace("\"formatVersion\":${IndexFormat.VERSION}", "\"formatVersion\":2"))
        shouldThrow<UnsupportedIndexFormatException> {
            NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL)
        }.message shouldContain "format v3"
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
        shouldThrow<UnsupportedIndexFormatException> {
            NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL)
        }
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
    fun `searchMulti limit zero probes without returning hits`() {
        val sources = File(tempDir, "multi-zero").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val members = listOf(KeepSetMember(gav = "demo:lib:1", sourceRoot = sources))
        val index = NameLocateIndex.build(
            members,
            TokenMode.IDENTS,
            KeepSetFingerprint.compute(TokenMode.IDENTS, "explicit", members),
            "explicit",
        )
        index.searchMulti(queries = listOf("Foo"), limit = 0, perQueryLimit = null) shouldContainExactly emptyList()
    }

    @Test
    fun `locate limit zero rejects out-of-range docId`() {
        val sources = File(tempDir, "limit-zero-oob").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-limit-zero-oob")
        index.writeTo(indexDir)

        val postings = readV3PostingEntries(File(indexDir, NameLocateIndex.POSTINGS_NAME))
        require(postings.isNotEmpty())
        val forgedBlob =
            GapEliasDeltaCodec.encodeOccurrences(listOf(OccPos(docId = 99, line = 1, column = 0)))
        val patchedAll = postings.map { (_, count) -> forgedBlob to count }
        writeV3Postings(File(indexDir, NameLocateIndex.POSTINGS_NAME), patchedAll)
        val reloaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL).trackForClose().shouldNotBeNull()
        shouldThrow<IllegalArgumentException> {
            reloaded.locate("Foo", limit = 0)
        }
    }

    @Test
    fun `locate rejects out-of-range docId at search time`() {
        val sources = File(tempDir, "src-oob").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-oob")
        index.writeTo(indexDir)

        val postings = readV3PostingEntries(File(indexDir, NameLocateIndex.POSTINGS_NAME))
        require(postings.isNotEmpty())
        val targetCount = postings.first().second
        require(targetCount >= 1) { "expected at least one occurrence to forge" }
        val forgedBlob =
            GapEliasDeltaCodec.encodeOccurrences(
                List(targetCount) { OccPos(docId = 99, line = it, column = 0) },
            )
        val patchedAll = postings.map { (_, count) -> forgedBlob to count }
        writeV3Postings(File(indexDir, NameLocateIndex.POSTINGS_NAME), patchedAll)
        val reloaded = NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL).trackForClose().shouldNotBeNull()
        shouldThrow<IllegalArgumentException> {
            reloaded.locate("Foo")
        }
    }

    @Test
    fun `rejects empty occCount with non-empty blob`() {
        val sources = File(tempDir, "src-empty-blob").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-empty-blob")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        val bytes = postingsFile.readBytes().toMutableList()
        // First offset-table row starts at byte 4: force count=0 with non-zero len (little-endian).
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        bytes[12] = 1
        postingsFile.writeBytes(bytes.toByteArray())
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects positive occCount with empty blob`() {
        val sources = File(tempDir, "src-empty-blob-positive-count").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-empty-blob-positive-count")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        val bytes = postingsFile.readBytes().toMutableList()
        // First offset-table row: keep count=1, force len=0 (little-endian).
        bytes[4] = 1
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        bytes[12] = 0
        bytes[13] = 0
        bytes[14] = 0
        bytes[15] = 0
        postingsFile.writeBytes(bytes.toByteArray())
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects implausible occurrence count for blob length`() {
        val sources = File(tempDir, "src-huge-count-small-blob").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-count-small-blob")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        val bytes = postingsFile.readBytes().toMutableList()
        // count=100 with len=1 exceeds len*8 (little-endian).
        bytes[4] = 100.toByte()
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        bytes[12] = 1
        bytes[13] = 0
        bytes[14] = 0
        bytes[15] = 0
        postingsFile.writeBytes(bytes.toByteArray())
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects absurd posting entry count`() {
        val sources = File(tempDir, "src-huge-count").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-count")
        index.writeTo(indexDir)

        val postingsFile = File(indexDir, NameLocateIndex.POSTINGS_NAME)
        val bytes = postingsFile.readBytes()
        // Overwrite name_count (first u32, little-endian) with Int.MAX_VALUE.
        require(bytes.size >= 4)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFF.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0x7F.toByte()
        postingsFile.writeBytes(bytes)
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects oversized string length in dictionary`() {
        val sources = File(tempDir, "src-huge-strlen").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-strlen")
        index.writeTo(indexDir)

        val dictionaryFile = File(indexDir, NameLocateIndex.DICTIONARY_NAME)
        val bytes = dictionaryFile.readBytes()
        // First string length is a big-endian u32 after magic+version+count: claim Int.MAX_VALUE.
        bytes[12] = 0x7F.toByte()
        bytes[13] = 0xFF.toByte()
        bytes[14] = 0xFF.toByte()
        bytes[15] = 0xFF.toByte()
        dictionaryFile.writeBytes(bytes)
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects oversized string length in documents`() {
        val sources = File(tempDir, "src-huge-docstrlen").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-docstrlen")
        index.writeTo(indexDir)

        val documentsFile = File(indexDir, NameLocateIndex.DOCUMENTS_NAME)
        val bytes = documentsFile.readBytes()
        // First gav length is a big-endian u32 after magic+version+docCount: claim Int.MAX_VALUE.
        bytes[12] = 0x7F.toByte()
        bytes[13] = 0xFF.toByte()
        bytes[14] = 0xFF.toByte()
        bytes[15] = 0xFF.toByte()
        documentsFile.writeBytes(bytes)
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects oversized name count in dictionary`() {
        val sources = File(tempDir, "src-huge-namecount").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-namecount")
        index.writeTo(indexDir)

        val dictionaryFile = File(indexDir, NameLocateIndex.DICTIONARY_NAME)
        val bytes = dictionaryFile.readBytes()
        // Name count is a big-endian u32 after magic+version: claim Int.MAX_VALUE.
        bytes[8] = 0x7F.toByte()
        bytes[9] = 0xFF.toByte()
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        dictionaryFile.writeBytes(bytes)
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }

    @Test
    fun `rejects oversized document count in documents`() {
        val sources = File(tempDir, "src-huge-doccount").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val members = listOf(KeepSetMember(gav = "g:a:1", sourceRoot = sources))
        val fingerprint = KeepSetFingerprint.compute(TokenMode.ALL, "explicit", members)
        val index = NameLocateIndex.build(members, TokenMode.ALL, fingerprint, "explicit")
        val indexDir = File(tempDir, "idx-huge-doccount")
        index.writeTo(indexDir)

        val documentsFile = File(indexDir, NameLocateIndex.DOCUMENTS_NAME)
        val bytes = documentsFile.readBytes()
        // Doc count is a big-endian u32 after magic+version: claim Int.MAX_VALUE.
        bytes[8] = 0x7F.toByte()
        bytes[9] = 0xFF.toByte()
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        documentsFile.writeBytes(bytes)
        NameLocateIndex.tryLoad(indexDir, fingerprint, TokenMode.ALL) shouldBe null
    }
}

private fun readV3PostingEntries(file: File): List<Pair<ByteArray, Int>> {
    val bytes = file.readBytes()
    val table = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val nameCount = table.getInt(0)
    val metas = List(nameCount) { id ->
        val entryBase = 4 + id * PostingsTable.POSTING_ENTRY_SIZE
        Triple(table.getInt(entryBase), table.getInt(entryBase + 4), table.getInt(entryBase + 8))
    }
    val dataBase = 4 + nameCount * PostingsTable.POSTING_ENTRY_SIZE
    return metas.map { (count, offset, len) ->
        bytes.copyOfRange(dataBase + offset, dataBase + offset + len) to count
    }
}

private fun writeV3Postings(file: File, entries: List<Pair<ByteArray, Int>>) {
    file.writeBytes(PostingsTable.packInMemory(entries.map { (blob, count) -> blob to count }))
}
