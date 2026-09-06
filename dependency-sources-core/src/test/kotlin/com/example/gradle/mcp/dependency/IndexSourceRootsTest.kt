package com.example.gradle.mcp.dependency

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IndexSourceRootsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `rejects path traversal segments`() {
        val root = File(tempDir, "safe").apply { mkdirs() }
        File(root, "Ok.kt").writeText("ok")
        File(tempDir, "secret.kt").writeText("secret")
        val roots = mapOf("g:n:1" to listOf(root))
        IndexSourceRoots.resolve(roots, "g:n:1", "../secret.kt") shouldBe SourceRootResolution.Missing
        val found = IndexSourceRoots.resolve(roots, "g:n:1", "Ok.kt")
        found.shouldBeInstanceOf<SourceRootResolution.Found>()
        (found as SourceRootResolution.Found).root.name shouldBe "safe"
    }

    @Test
    fun `round-trips roots and resolves directory path`() {
        val root = File(tempDir, "src").apply { mkdirs() }
        File(root, "A.kt").writeText("class A")
        val dir = File(tempDir, "index").apply { mkdirs() }
        IndexSourceRoots.write(
            dir,
            listOf(KeepSetMember(gav = "g:n:1", sourceRoot = root)),
        )
        val loaded = IndexSourceRoots.load(dir)
        val found = IndexSourceRoots.resolve(loaded, "g:n:1", "A.kt")
        found.shouldBeInstanceOf<SourceRootResolution.Found>()
        (found as SourceRootResolution.Found).root.absolutePath shouldBe root.absolutePath
        IndexSourceRoots.resolve(loaded, "g:n:1", "Missing.kt") shouldBe SourceRootResolution.Missing
    }

    @Test
    fun `single jar root without entry returns missing`() {
        val jar = File(tempDir, "lib-sources.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Only.kt"))
            zip.write("class Only".toByteArray())
            zip.closeEntry()
        }
        val roots = mapOf("g:n:1" to listOf(jar))
        IndexSourceRoots.resolve(roots, "g:n:1", "Missing.kt") shouldBe SourceRootResolution.Missing
        val found = IndexSourceRoots.resolve(roots, "g:n:1", "Only.kt")
        found.shouldBeInstanceOf<SourceRootResolution.Found>()
        (found as SourceRootResolution.Found).root.name shouldBe "lib-sources.jar"
    }

    @Test
    fun `jar entry cache is reused across resolves`() {
        val jar = File(tempDir, "cached-sources.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("B.kt"))
            zip.write("class B".toByteArray())
            zip.closeEntry()
        }
        val roots = mapOf("g:n:1" to listOf(jar))
        val cache = HashMap<String, Set<String>>()
        val found = IndexSourceRoots.resolve(roots, "g:n:1", "B.kt", cache)
        found.shouldBeInstanceOf<SourceRootResolution.Found>()
        (found as SourceRootResolution.Found).root.name shouldBe "cached-sources.jar"
        IndexSourceRoots.resolve(roots, "g:n:1", "Missing.kt", cache) shouldBe SourceRootResolution.Missing
        cache.size shouldBe 1
        cache.values.single().shouldContain("B.kt")
    }

    @Test
    fun `ambiguous roots with same path return Ambiguous`() {
        val a = File(tempDir, "root-a").apply { mkdirs() }
        val b = File(tempDir, "root-b").apply { mkdirs() }
        File(a, "Shared.kt").writeText("class SharedA")
        File(b, "Shared.kt").writeText("class SharedB")
        val roots = mapOf("g:n:1" to listOf(a, b))
        IndexSourceRoots.resolve(roots, "g:n:1", "Shared.kt") shouldBe SourceRootResolution.Ambiguous
    }
}
