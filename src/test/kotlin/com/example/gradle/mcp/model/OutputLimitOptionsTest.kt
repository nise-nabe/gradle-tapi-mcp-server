package com.example.gradle.mcp.model

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputLimitOptionsTest {
    @Test
    fun `fromArgs uses defaults when omitted`() {
        val options = OutputLimitOptions.fromArgs(emptyMap())

        options.includeOutput.shouldBeFalse()
        options.maxOutputChars shouldBe OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS
        options.tailOutput.shouldBeTrue()
    }

    @Test
    fun `fromArgs parses custom limits`() {
        val options = OutputLimitOptions.fromArgs(
            mapOf(
                "includeOutput" to true,
                "maxOutputChars" to 1200,
                "tailOutput" to false,
            ),
        )

        options.includeOutput.shouldBeTrue()
        options.maxOutputChars shouldBe 1200
        options.tailOutput.shouldBeFalse()
    }
}
