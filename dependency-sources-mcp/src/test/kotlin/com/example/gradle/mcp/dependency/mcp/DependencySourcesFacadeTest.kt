package com.example.gradle.mcp.dependency.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DependencySourcesFacadeTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `indexes sourcePaths and searches with matching tokenMode`() {
        val sources = File(tempDir, "src").apply { mkdirs() }
        File(sources, "Demo.kt").writeText(
            """
            // Foo helper
            class Bar { fun Foo() {} }
            """.trimIndent(),
        )
        val project = File(tempDir, "project").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()

        val indexed = facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "all",
            ),
            access,
        )
        indexed["cacheHit"] shouldBe false
        indexed["tokenMode"] shouldBe "all"
        indexed["formatVersion"] shouldBe 2

        val searchAll = facade.search(
            mapOf("query" to "Foo", "tokenMode" to "all"),
            access,
        )
        @Suppress("UNCHECKED_CAST")
        val hitsAll = searchAll["hits"] as List<Map<String, Any?>>
        hitsAll.map { it["line"] as Int }.sorted() shouldContainExactly listOf(1, 2)

        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )
        val searchIdents = facade.search(
            mapOf("query" to "Foo", "tokenMode" to "idents"),
            access,
        )
        @Suppress("UNCHECKED_CAST")
        val hitsIdents = searchIdents["hits"] as List<Map<String, Any?>>
        hitsIdents.map { it["line"] as Int } shouldContainExactly listOf(2)
    }

    @Test
    fun `search without index fails with guidance`() {
        val project = File(tempDir, "empty-project").apply { mkdirs() }
        val access = StubAccess(project)
        val error = shouldThrow<IllegalArgumentException> {
            DependencySourcesFacade().search(mapOf("query" to "Foo"), access)
        }
        error.message shouldContain "gradle_index_dependency_sources"
    }

    @Test
    fun `tokenMode mismatch does not silently reuse other mode index`() {
        val sources = File(tempDir, "src2").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}")
        val project = File(tempDir, "proj2").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "all",
            ),
            access,
        )
        val error = shouldThrow<IllegalArgumentException> {
            facade.search(mapOf("query" to "Foo", "tokenMode" to "idents"), access)
        }
        error.message shouldContain "gradle_index_dependency_sources"
    }

    @Test
    fun `non-array artifacts is rejected instead of falling back to Idea keep-set`() {
        val project = File(tempDir, "proj3").apply { mkdirs() }
        val access = StubAccess(project)
        val error = shouldThrow<IllegalArgumentException> {
            DependencySourcesFacade().index(
                mapOf("artifacts" to "com.example:lib:1.0"),
                access,
            )
        }
        error.message shouldContain "artifacts must be an array"
    }

    @Test
    fun `non-array sourcePaths is rejected`() {
        val project = File(tempDir, "proj4").apply { mkdirs() }
        val access = StubAccess(project)
        val error = shouldThrow<IllegalArgumentException> {
            DependencySourcesFacade().index(
                mapOf("sourcePaths" to mapOf("path" to "/tmp/x")),
                access,
            )
        }
        error.message shouldContain "sourcePaths must be an array"
    }
}

private class StubAccess(
    private val projectDirectory: File,
) : DependencySourcesGradleAccess {
    override fun resolveProjectDirectory(args: Map<String, Any>): File = projectDirectory

    override fun <T> withConnection(projectDirectory: File, block: (ProjectConnection) -> T): T =
        error("connection should not be required for explicit sourcePaths")

    override fun <T> withNoActiveBuild(projectDirectory: File, block: () -> T): T = block()
}
