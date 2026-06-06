package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JavaVersionResolverTest {
    @Test
    fun `reads java version from release file`() {
        val tempHome = Files.createTempDirectory("java-home").toFile()
        try {
            File(tempHome, "release").writeText("""JAVA_VERSION="21.0.2"""")
            assertEquals("21.0.2", JavaVersionResolver.resolve(tempHome))
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `returns null when release file is missing`() {
        val tempHome = Files.createTempDirectory("java-home-empty").toFile()
        try {
            assertNull(JavaVersionResolver.resolve(tempHome))
        } finally {
            tempHome.deleteRecursively()
        }
    }
}
