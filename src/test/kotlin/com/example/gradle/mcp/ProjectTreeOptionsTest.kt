package com.example.gradle.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProjectTreeOptionsTest {
    @Test
    fun `fromArgs parses depth and children limits`() {
        val options = ProjectTreeOptions.fromArgs(
            mapOf(
                "maxDepth" to 2,
                "maxChildren" to 5,
            ),
        )

        assertEquals(2, options.maxDepth)
        assertEquals(5, options.maxChildren)
    }

    @Test
    fun `fromArgs accepts root-only maxDepth and rejects invalid children limits`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("maxDepth" to 0, "maxChildren" to -1))

        assertEquals(0, options.maxDepth)
        assertNull(options.maxChildren)
    }
}
