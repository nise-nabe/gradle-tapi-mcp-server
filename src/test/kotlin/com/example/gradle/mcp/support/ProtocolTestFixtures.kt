package com.example.gradle.mcp.support

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

internal fun assertInvalidArgument(block: () -> Unit, message: String) {
    val error = shouldThrow<McpException>(block)
    error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    error.message shouldBe message
}
