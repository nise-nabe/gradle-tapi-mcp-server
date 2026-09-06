package com.example.gradle.mcp.dependency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    fun `searchMulti limit zero runs decode probe`() {
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

    @Test
    fun `searchMulti per query limit zero reports truncation when matches exist`() {
        val sources = File(tempDir, "src-per-query-zero").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-per-query-zero").apply { mkdirs() }
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
                perQueryLimit = 0,
            ),
        )
        result.hitCount shouldBe 0
        result.hitsTruncated shouldBe true
    }

    @Test
    fun `searchMulti per query limit reports truncation when overall limit omitted`() {
        val sources = File(tempDir, "src-per-query-trunc").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-per-query-trunc").apply { mkdirs() }
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
                perQueryLimit = 1,
            ),
        )
        result.hitCount shouldBe 1
        result.hitsTruncated shouldBe true
    }

    @Test
    fun `search rejects stale v2 index with actionable error`() {
        val sources = File(tempDir, "src-stale").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-stale").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )
        val indexDir = store.defaultIndexDir(project, TokenMode.IDENTS)
        val manifest = File(indexDir, NameLocateIndex.MANIFEST_NAME)
        manifest.writeText(
            manifest.readText().replace(
                "\"formatVersion\":${IndexFormat.VERSION}",
                "\"formatVersion\":2",
            ),
        )

        val searchStore = DependencyIndexStore()
        val error = shouldThrow<UnsupportedIndexFormatException> {
            searchStore.search(
                SearchRequest(
                    projectDirectory = project,
                    query = "Foo",
                    tokenMode = TokenMode.IDENTS,
                ),
            )
        }
        error.message shouldContain "format v3"
    }

    @Test
    fun `search without tokenMode falls back past stale ALL to valid IDENTS`() {
        val sourcesAll = File(tempDir, "src-all-stale").apply { mkdirs() }
        File(sourcesAll, "A.kt").writeText("fun AllOnly() {}\n")
        val sourcesIdents = File(tempDir, "src-idents-ok").apply { mkdirs() }
        File(sourcesIdents, "B.kt").writeText("fun IdentsHit() {}\n")
        val project = File(tempDir, "proj-fallback").apply { mkdirs() }
        val store = DependencyIndexStore()

        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.ALL,
                sourcePaths = listOf(SourcePathRef(path = sourcesAll)),
            ),
            connection = null,
        )
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sourcesIdents)),
            ),
            connection = null,
        )

        val allManifest = File(store.defaultIndexDir(project, TokenMode.ALL), NameLocateIndex.MANIFEST_NAME)
        allManifest.writeText(
            allManifest.readText().replace(
                "\"formatVersion\":${IndexFormat.VERSION}",
                "\"formatVersion\":2",
            ),
        )

        val fresh = DependencyIndexStore()
        val result = fresh.search(
            SearchRequest(
                projectDirectory = project,
                query = "IdentsHit",
                tokenMode = null,
            ),
        )
        result.hitCount shouldBe 1
        result.hits.single().path shouldBe "B.kt"
        result.stats.tokenMode shouldBe TokenMode.IDENTS
    }

    @Test
    fun `forceReindex succeeds after mmap-backed search warm cache`() {
        val sources = File(tempDir, "src-reindex").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun ReindexMe() {}\n")
        val project = File(tempDir, "proj-reindex").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        // Load mmap-backed index into the store cache.
        val warm = DependencyIndexStore()
        warm.search(
            SearchRequest(
                projectDirectory = project,
                query = "ReindexMe",
                tokenMode = TokenMode.IDENTS,
            ),
        ).hitCount shouldBe 1

        File(sources, "A.kt").writeText("fun ReindexMe() {}\nfun AfterRebuild() {}\n")
        val rebuilt = warm.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
                forceReindex = true,
            ),
            connection = null,
        )
        rebuilt.stats.cacheHit shouldBe false

        warm.search(
            SearchRequest(
                projectDirectory = project,
                query = "AfterRebuild",
                tokenMode = TokenMode.IDENTS,
            ),
        ).hitCount shouldBe 1
    }

    @Test
    fun `negative limit is rejected at store layer`() {
        val sources = File(tempDir, "src-neg-store").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-neg-store").apply { mkdirs() }
        val store = DependencyIndexStore()
        store.index(
            IndexRequest(
                projectDirectory = project,
                tokenMode = TokenMode.IDENTS,
                sourcePaths = listOf(SourcePathRef(path = sources)),
            ),
            connection = null,
        )

        val error = shouldThrow<IllegalArgumentException> {
            store.search(
                SearchRequest(
                    projectDirectory = project,
                    query = "Foo",
                    tokenMode = TokenMode.IDENTS,
                    limit = -1,
                ),
            )
        }
        error.message shouldContain "non-negative"
    }
}
