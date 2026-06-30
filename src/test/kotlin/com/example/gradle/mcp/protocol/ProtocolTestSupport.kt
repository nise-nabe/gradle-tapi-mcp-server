package com.example.gradle.mcp.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

internal fun assertInvalidArgument(block: () -> Unit, message: String) {
    val error = shouldThrow<McpException>(block)
    error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    error.message shouldBe message
}

internal fun assertInvalidArgumentContains(block: () -> Unit, substring: String) {
    val error = shouldThrow<McpException>(block)
    error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    error.message.shouldContain(substring)
}
