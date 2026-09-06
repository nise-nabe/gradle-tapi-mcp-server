package com.example.gradle.mcp.dependency

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DependencyIndexStoreSearchTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `hitsTruncated is false when hit count equals limit exactly`() {
        val sources = File(tempDir, "src").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        val exact = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "Foo",
                tokenMode = TokenMode.IDENTS,
                limit = 3,
            ),
        )
        exact.hitCount shouldBe 3
        exact.hitsTruncated shouldBe false

        val truncated = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "Foo",
                tokenMode = TokenMode.IDENTS,
                limit = 2,
            ),
        )
        truncated.hitCount shouldBe 2
        truncated.hitsTruncated shouldBe true
    }

    @Test
    fun `Int MAX_VALUE limit does not overflow probe and still returns hits`() {
        val sources = File(tempDir, "src-max").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-max").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )
        val result = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "Foo",
                tokenMode = TokenMode.IDENTS,
                limit = Int.MAX_VALUE,
            ),
        )
        result.hitCount shouldBe 1
        result.hitsTruncated shouldBe false
    }

    @Test
    fun `limit zero returns no hits and reports truncation when matches exist`() {
        val sources = File(tempDir, "src-zero").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-zero").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )
        val withMatches = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "Foo",
                tokenMode = TokenMode.IDENTS,
                limit = 0,
            ),
        )
        withMatches.hitCount shouldBe 0
        withMatches.hitsTruncated shouldBe true

        val withoutMatches = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "MissingName",
                tokenMode = TokenMode.IDENTS,
                limit = 0,
            ),
        )
        withoutMatches.hitCount shouldBe 0
        withoutMatches.hitsTruncated shouldBe false
    }

    @Test
    fun `omitted limit returns all hits`() {
        val sources = File(tempDir, "src-unlimited").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-unlimited").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        val result = store.search(
            SearchRequest(
                projectDirectory = project,
                query = "Foo",
                tokenMode = TokenMode.IDENTS,
                limit = null,
            ),
        )
        result.hitCount shouldBe 3
        result.hitsTruncated shouldBe false
    }

    @Test
    fun `searchMulti overall limit truncates merged hits`() {
        val sources = File(tempDir, "src-multi").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Bar() {}\n")
        val project = File(tempDir, "proj-multi").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        val result = store.searchMulti(
            SearchMultiRequest(
                projectDirectory = project,
                queries = listOf("Foo", "Bar"),
                tokenMode = TokenMode.IDENTS,
                limit = 1,
            ),
        )
        result.hitCount shouldBe 1
        result.hitsTruncated shouldBe true
        result.hits.single().matchedQueries shouldContainExactly listOf("Foo")
    }

    @Test
    fun `searchMulti limit zero short circuits`() {
        val sources = File(tempDir, "src-multi-zero").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-multi-zero").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        val result = store.searchMulti(
            SearchMultiRequest(
                projectDirectory = project,
                queries = listOf("Foo"),
                tokenMode = TokenMode.IDENTS,
                limit = 0,
            ),
        )
        result.hitCount shouldBe 0
        result.hitsTruncated shouldBe true
    }
}
