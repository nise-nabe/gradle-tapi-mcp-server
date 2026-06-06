package com.example.mcp

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
    fun `fromArgs ignores non-positive limits`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("maxDepth" to 0, "maxChildren" to -1))

        assertNull(options.maxDepth)
        assertNull(options.maxChildren)
    }
}
