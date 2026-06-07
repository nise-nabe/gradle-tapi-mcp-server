package com.example.gradle.mcp.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

        options.maxDepth shouldBe 2
        options.maxChildren shouldBe 5
    }

    @Test
    fun `fromArgs accepts root-only maxDepth and rejects invalid children limits`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("maxDepth" to 0, "maxChildren" to -1))

        options.maxDepth shouldBe 0
        options.maxChildren.shouldBeNull()
    }
}
