package com.example.gradle.mcp.dependency

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IndexSourceRootsTest {
    @Test
    fun `rejects path traversal segments`() {
        val root = File(tempDir, "safe").apply { mkdirs() }
        File(root, "Ok.kt").writeText("ok")
        File(tempDir, "secret.kt").writeText("secret")
        val roots = mapOf("g:n:1" to listOf(root))
        IndexSourceRoots.resolve(roots, "g:n:1", "../secret.kt").shouldBeNull()
        IndexSourceRoots.resolve(roots, "g:n:1", "Ok.kt")!!.name shouldBe "safe"
    }

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
    fun `jar entry cache is reused across resolves`() {
        val jar = File(tempDir, "cached-sources.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("B.kt"))
            zip.write("class B".toByteArray())
            zip.closeEntry()
        }
        val roots = mapOf("g:n:1" to listOf(jar))
        val cache = HashMap<String, Set<String>>()
        IndexSourceRoots.resolve(roots, "g:n:1", "B.kt", cache)!!.name shouldBe "cached-sources.jar"
        IndexSourceRoots.resolve(roots, "g:n:1", "Missing.kt", cache).shouldBeNull()
        cache.size shouldBe 1
        cache.values.single().shouldContain("B.kt")
    }
}
