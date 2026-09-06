package com.example.gradle.mcp.dependency

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
}
