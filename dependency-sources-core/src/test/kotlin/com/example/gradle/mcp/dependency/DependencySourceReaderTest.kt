package com.example.gradle.mcp.dependency

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencySourceReaderTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `reads snippet around line from sources jar via gradle cache`() {
        val home = File(tempDir, "gradle-home")
        placeSourcesJar(
            gradleUserHome = home,
            group = "org.example",
            name = "lib",
            version = "1.0.0",
            entryPath = "org/example/Demo.java",
            content =
                """
                package org.example;

                public class Demo {
                    // line 4
                    public void run() {}
                }
                """.trimIndent(),
        )

        val result = DependencySourceReader.read(
            ReadSourceRequest(
                artifact = DependencyArtifactRef("org.example", "lib", "1.0.0"),
                path = "org/example/Demo.java",
                line = 4,
                contextLines = 1,
                gradleUserHome = home,
            ),
        )

        result.startLine shouldBe 3
        result.endLine shouldBe 5
        result.truncated shouldBe true
        result.lineCount shouldBe 6
        result.snippet shouldContain "public class Demo"
        result.snippet shouldContain "// line 4"
        result.snippet shouldContain "public void run"
    }

    @Test
    fun `reads whole file when line omitted`() {
        val jar = File(tempDir, "plain-sources.jar")
        writeJar(jar, "A.kt", "fun one()\nfun two()")

        val result = DependencySourceReader.read(
            ReadSourceRequest(
                artifact = DependencyArtifactRef("g", "n", "1"),
                path = "A.kt",
                sourceRoot = jar,
            ),
        )

        result.startLine shouldBe 1
        result.endLine shouldBe 2
        result.truncated shouldBe false
        result.snippet shouldBe "fun one()\nfun two()"
    }

    @Test
    fun `reads from source directory via sourceRoot`() {
        val tree = File(tempDir, "src").apply { mkdirs() }
        File(tree, "pkg").mkdirs()
        File(tree, "pkg/Hello.kt").writeText("class Hello\n")

        val result = DependencySourceReader.read(
            ReadSourceRequest(
                artifact = DependencyArtifactRef("local", "tree", "0"),
                path = "pkg/Hello.kt",
                sourceRoot = tree,
            ),
        )

        result.snippet shouldBe "class Hello"
        result.truncated shouldBe false
    }

    @Test
    fun `rejects path traversal under directory sourceRoot`() {
        val tree = File(tempDir, "safe").apply { mkdirs() }
        File(tree, "Ok.kt").writeText("ok\n")
        File(tempDir, "secret.kt").writeText("secret\n")

        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "../secret.kt",
                    sourceRoot = tree,
                ),
            )
        }.message shouldContain "escapes"
    }

    @Test
    fun `parseGav requires three parts`() {
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.parseGav("only:two")
        }
        DependencySourceReader.parseGav("a.b:c:1.2.3").gav() shouldBe "a.b:c:1.2.3"
    }


    @Test
    fun `rejects blank path after normalization`() {
        val jar = File(tempDir, "blank-path.jar")
        writeJar(jar, "A.kt", "fun a()")
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "  ///  ",
                    sourceRoot = jar,
                ),
            )
        }.message shouldContain "path must not be blank"
    }

    @Test
    fun `caps whole file read with maxLines`() {
        val jar = File(tempDir, "long-sources.jar")
        writeJar(jar, "Long.kt", (1..10).joinToString("\n") { "line$it" })

        val result = DependencySourceReader.read(
            ReadSourceRequest(
                artifact = DependencyArtifactRef("g", "n", "1"),
                path = "Long.kt",
                maxLines = 3,
                sourceRoot = jar,
            ),
        )

        result.startLine shouldBe 1
        result.endLine shouldBe 3
        result.lineCount shouldBe 10
        result.truncated shouldBe true
        result.snippet shouldBe "line1\nline2\nline3"
    }

    @Test
    fun `rejects line past end of file`() {
        val jar = File(tempDir, "short.jar")
        writeJar(jar, "S.kt", "one\ntwo")
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "S.kt",
                    line = 9,
                    sourceRoot = jar,
                ),
            )
        }.message shouldContain "past end of file"
    }

    @Test
    fun `rejects non-source path extension`() {
        val jar = File(tempDir, "mixed.jar")
        writeJar(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "META-INF/MANIFEST.MF",
                    sourceRoot = jar,
                ),
            )
        }.message shouldContain "source file"
    }

    @Test
    fun `sources not found without sourceRoot or cache jar`() {
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("missing.group", "none", "0.0.0"),
                    path = "A.kt",
                    gradleUserHome = File(tempDir, "empty-home").apply { mkdirs() },
                ),
            )
        }.message shouldContain "Sources not found"
    }


    @Test
    fun `rejects contextLines above max`() {
        val jar = File(tempDir, "cap.jar")
        writeJar(jar, "A.kt", "fun a()")
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "A.kt",
                    contextLines = ReadSourceRequest.MAX_CONTEXT_LINES + 1,
                    sourceRoot = jar,
                ),
            )
        }.message shouldContain "contextLines"
    }

    @Test
    fun `rejects maxLines above max`() {
        val jar = File(tempDir, "cap2.jar")
        writeJar(jar, "A.kt", "fun a()")
        shouldThrow<IllegalArgumentException> {
            DependencySourceReader.read(
                ReadSourceRequest(
                    artifact = DependencyArtifactRef("g", "n", "1"),
                    path = "A.kt",
                    maxLines = ReadSourceRequest.MAX_MAX_LINES + 1,
                    sourceRoot = jar,
                ),
            )
        }.message shouldContain "maxLines"
    }

    private fun placeSourcesJar(
        gradleUserHome: File,
        group: String,
        name: String,
        version: String,
        entryPath: String,
        content: String,
    ): File {
        val moduleDir = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/$group/$name/$version/hash",
        )
        moduleDir.mkdirs()
        val jar = File(moduleDir, "$name-$version-sources.jar")
        writeJar(jar, entryPath, content)
        return jar
    }

    private fun writeJar(jar: File, entryPath: String, content: String) {
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryPath))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
