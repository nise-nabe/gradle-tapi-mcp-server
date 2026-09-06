package com.example.gradle.mcp.dependency

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IndexSourceRootsTest {
    @TempDir
    lateinit var tempDir: File

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
        IndexSourceRoots.resolve(loaded, "g:n:1", "A.kt")!!.absolutePath shouldBe root.absolutePath
        IndexSourceRoots.resolve(loaded, "g:n:1", "Missing.kt").shouldBeNull()
    }

    @Test
    fun `single jar root without entry returns null`() {
        val jar = File(tempDir, "lib-sources.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Only.kt"))
            zip.write("class Only".toByteArray())
            zip.closeEntry()
        }
        val roots = mapOf("g:n:1" to listOf(jar))
        IndexSourceRoots.resolve(roots, "g:n:1", "Missing.kt").shouldBeNull()
        IndexSourceRoots.resolve(roots, "g:n:1", "Only.kt")!!.name shouldBe "lib-sources.jar"
    }

    @Test
    fun `memoized resolve does not change results`() {
        val root = File(tempDir, "tree").apply { mkdirs() }
        File(root, "B.kt").writeText("class B")
        val roots = mapOf("g:n:1" to listOf(root))
        val cache = HashMap<String, Boolean>()
        IndexSourceRoots.resolve(roots, "g:n:1", "B.kt", cache)!!.name shouldBe "tree"
        IndexSourceRoots.resolve(roots, "g:n:1", "B.kt", cache)!!.name shouldBe "tree"
        cache.isNotEmpty() shouldBe true
    }
}
