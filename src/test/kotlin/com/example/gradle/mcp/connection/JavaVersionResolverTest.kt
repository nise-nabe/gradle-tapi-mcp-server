package com.example.gradle.mcp.connection

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JavaVersionResolverTest {
    @Test
    fun `reads java version from release file`() {
        val tempHome = Files.createTempDirectory("java-home").toFile()
        try {
            File(tempHome, "release").writeText("""JAVA_VERSION="21.0.2"""")
            JavaVersionResolver.resolve(tempHome) shouldBe "21.0.2"
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `returns null when release file is missing`() {
        val tempHome = Files.createTempDirectory("java-home-empty").toFile()
        try {
            JavaVersionResolver.resolve(tempHome).shouldBeNull()
        } finally {
            tempHome.deleteRecursively()
        }
    }
}
