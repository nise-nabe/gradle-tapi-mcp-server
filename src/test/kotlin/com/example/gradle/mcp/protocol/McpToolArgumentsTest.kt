package com.example.gradle.mcp.protocol

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class McpToolArgumentsTest {
    @Test
    fun `optionalPositiveInt accepts integral numbers and strings`() {
        mapOf("limit" to 5).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to 5L).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to "10").optionalPositiveInt("limit") shouldBe 10
    }

    @Test
    fun `optionalPositiveInt rejects non-integral and out-of-range numbers`() {
        mapOf("limit" to 1.9).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to 0).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to -3).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to Long.MAX_VALUE).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to "not-a-number").optionalPositiveInt("limit") shouldBe null
    }

    @Test
    fun `optionalNonNegativeInt accepts zero and rejects fractional values`() {
        mapOf("offset" to 0).optionalNonNegativeInt("offset") shouldBe 0
        mapOf("offset" to 2.5).optionalNonNegativeInt("offset") shouldBe null
        mapOf("offset" to -1).optionalNonNegativeInt("offset") shouldBe null
    }
}
