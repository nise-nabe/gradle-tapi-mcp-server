package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GradleArgumentPolicyTest {
    @Test
    fun `rejects init script flag forms`() {
        val cases = listOf(
            listOf("--init-script", "evil.gradle"),
            listOf("--init-script=evil.gradle"),
            listOf("-I", "evil.gradle"),
            listOf("-Ievil.gradle"),
            listOf("-Init.gradle"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoInitScript(arguments)
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects gradle argument files`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.requireNoInitScript(listOf("@args.gradle"))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `allows regular gradle arguments`() {
        GradleArgumentPolicy.requireNoInitScript(
            listOf("--info", "-Dorg.gradle.parallel=true", "-Pfoo=bar"),
        )
    }
}
