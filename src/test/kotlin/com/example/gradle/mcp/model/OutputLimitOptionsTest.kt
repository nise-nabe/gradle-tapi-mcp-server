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

    @Test
    fun `fromArgs accepts zero maxOutputChars and rejects negative values`() {
        val zeroLimit = OutputLimitOptions.fromArgs(mapOf("maxOutputChars" to 0))

        zeroLimit.maxOutputChars shouldBe 0

        val negativeLimit = OutputLimitOptions.fromArgs(mapOf("maxOutputChars" to -1))

        negativeLimit.maxOutputChars shouldBe OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS
    }
}
