package com.example.gradle.mcp.dependency.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
        indexed["formatVersion"] shouldBe 3

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
    fun `search limit one returns single hit`() {
        val sources = File(tempDir, "src-limit").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-limit").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val search = facade.search(
            mapOf("query" to "Foo", "tokenMode" to "idents", "limit" to 1),
            access,
        )
        search["hitCount"] shouldBe 1
        search["hitsTruncated"] shouldBe true
        @Suppress("UNCHECKED_CAST")
        val hits = search["hits"] as List<Map<String, Any?>>
        hits.single()["line"] shouldBe 1
    }

    @Test
    fun `searchMulti returns matchedQueries tags`() {
        val sources = File(tempDir, "src-multi-facade").apply { mkdirs() }
        File(sources, "A.kt").writeText("class A { HttpClient a; Foo b; }\n")
        val project = File(tempDir, "proj-multi-facade").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val search = facade.searchMulti(
            mapOf("queries" to listOf("HttpClient", "Foo"), "tokenMode" to "idents"),
            access,
        )
        search["hitCount"] shouldBe 2
        @Suppress("UNCHECKED_CAST")
        val hits = search["hits"] as List<Map<String, Any?>>
        hits.single { (it["matchedQueries"] as List<String>).contains("HttpClient") }["matchedQueries"] shouldBe
            listOf("HttpClient")
    }

    @Test
    fun `searchMulti explicit null perQueryLimit wins over per_query_limit alias`() {
        val sources = File(tempDir, "src-alias-null").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-alias-null").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        @Suppress("UNCHECKED_CAST")
        val search = facade.searchMulti(
            linkedMapOf<String, Any?>(
                "queries" to listOf("Foo"),
                "tokenMode" to "idents",
                "perQueryLimit" to null,
                "per_query_limit" to 1,
            ) as Map<String, Any>,
            access,
        )
        search["hitCount"] shouldBe 2
        search["hitsTruncated"] shouldBe false
    }

    @Test
    fun `searchMulti accepts per_query_limit alias`() {
        val sources = File(tempDir, "src-alias").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-alias").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val search = facade.searchMulti(
            mapOf("queries" to listOf("Foo"), "tokenMode" to "idents", "per_query_limit" to 1),
            access,
        )
        search["hitCount"] shouldBe 1
        search["hitsTruncated"] shouldBe true
    }

    @Test
    fun `search limit zero returns empty`() {
        val sources = File(tempDir, "src-zero-facade").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-zero-facade").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val search = facade.search(
            mapOf("query" to "Foo", "tokenMode" to "idents", "limit" to 0),
            access,
        )
        search["hitCount"] shouldBe 0
        search["hitsTruncated"] shouldBe true
    }

    @Test
    fun `negative limit is rejected`() {
        val sources = File(tempDir, "src-neg").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-neg").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val error = shouldThrow<IllegalArgumentException> {
            facade.search(mapOf("query" to "Foo", "tokenMode" to "idents", "limit" to -1), access)
        }
        error.message shouldContain "non-negative"
    }

    @Test
    fun `fractional limit is rejected`() {
        val sources = File(tempDir, "src-frac").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\n")
        val project = File(tempDir, "proj-frac").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        val error = shouldThrow<IllegalArgumentException> {
            facade.search(mapOf("query" to "Foo", "tokenMode" to "idents", "limit" to 1.5), access)
        }
        error.message shouldContain "non-negative integer"
    }

    @Test
    fun `explicit null limit behaves as unlimited`() {
        val sources = File(tempDir, "src-null-limit").apply { mkdirs() }
        File(sources, "A.kt").writeText("fun Foo() {}\nfun Foo() {}\n")
        val project = File(tempDir, "proj-null-limit").apply { mkdirs() }
        val access = StubAccess(project)
        val facade = DependencySourcesFacade()
        facade.index(
            mapOf(
                "sourcePaths" to listOf(mapOf("path" to sources.absolutePath)),
                "tokenMode" to "idents",
            ),
            access,
        )

        @Suppress("UNCHECKED_CAST")
        val args = linkedMapOf<String, Any?>(
            "query" to "Foo",
            "tokenMode" to "idents",
            "limit" to null,
        ) as Map<String, Any>
        val search = facade.search(args, access)
        search["hitCount"] shouldBe 2
        search["hitsTruncated"] shouldBe false
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

    @Test
    fun `artifacts index uses explicit gradleUserHome for sources jar lookup`() {
        val gradleHome = File(tempDir, "explicit-ghome")
        val jar = placeSourcesJar(gradleHome, "com.example", "explicit-lib", "1.0.0")
        val project = File(tempDir, "proj-explicit-ghome").apply { mkdirs() }
        val access = StubAccess(project, connectedGradleUserHome = File(tempDir, "should-not-use"))
        val facade = DependencySourcesFacade()

        val indexed = facade.index(
            mapOf(
                "artifacts" to listOf(
                    mapOf("group" to "com.example", "name" to "explicit-lib", "version" to "1.0.0"),
                ),
                "gradleUserHome" to gradleHome.absolutePath,
                "tokenMode" to "idents",
            ),
            access,
        )
        indexed["memberCount"] shouldBe 1
        indexed["keepSetMode"] shouldBe "explicit"

        val search = facade.search(mapOf("query" to "ExplicitLib", "tokenMode" to "idents"), access)
        @Suppress("UNCHECKED_CAST")
        val hits = search["hits"] as List<Map<String, Any?>>
        hits.shouldHaveSize(1)
        hits.single()["path"] shouldBe "ExplicitLib.kt"
        jar.isFile shouldBe true
    }

    @Test
    fun `artifacts index uses connected gradleUserHome when arg omitted`() {
        val connectedHome = File(tempDir, "connected-ghome")
        placeSourcesJar(connectedHome, "com.example", "connected-lib", "2.0.0")
        val project = File(tempDir, "proj-connected-ghome").apply { mkdirs() }
        val access = StubAccess(project, connectedGradleUserHome = connectedHome)
        val facade = DependencySourcesFacade()

        val indexed = facade.index(
            mapOf(
                "artifacts" to listOf(
                    mapOf("group" to "com.example", "name" to "connected-lib", "version" to "2.0.0"),
                ),
                "tokenMode" to "idents",
            ),
            access,
        )
        indexed["memberCount"] shouldBe 1
        indexed["keepSetMode"] shouldBe "explicit"
    }

    @Test
    fun `artifacts index fails when jar only exists under unused connected home`() {
        val unusedHome = File(tempDir, "unused-connected")
        placeSourcesJar(unusedHome, "com.example", "wrong-home", "3.0.0")
        val project = File(tempDir, "proj-wrong-home").apply { mkdirs() }
        val access = StubAccess(project, connectedGradleUserHome = File(tempDir, "empty-home").apply { mkdirs() })

        val error = shouldThrow<IllegalArgumentException> {
            DependencySourcesFacade().index(
                mapOf(
                    "artifacts" to listOf(
                        mapOf("group" to "com.example", "name" to "wrong-home", "version" to "3.0.0"),
                    ),
                ),
                access,
            )
        }
        error.message shouldContain "com.example:wrong-home:3.0.0"
    }

    private fun placeSourcesJar(
        gradleUserHome: File,
        group: String,
        name: String,
        version: String,
    ): File {
        val moduleDir = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/$group/$name/$version/hash",
        )
        moduleDir.mkdirs()
        val jar = File(moduleDir, "$name-$version-sources.jar")
        val simpleName = name.split('-').joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
        java.util.zip.ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("$simpleName.kt"))
            zip.write("class $simpleName\n".toByteArray())
            zip.closeEntry()
        }
        return jar
    }
}

private class StubAccess(
    private val projectDirectory: File,
    private val connectedGradleUserHome: File? = null,
) : DependencySourcesGradleAccess {
    override fun resolveProjectDirectory(args: Map<String, Any>): File = projectDirectory

    override fun gradleUserHome(projectDirectory: File): File? = connectedGradleUserHome

    override fun <T> withConnection(projectDirectory: File, block: (ProjectConnection) -> T): T =
        error("connection should not be required for explicit sourcePaths")

    override fun <T> withNoActiveBuild(projectDirectory: File, block: () -> T): T = block()
}
