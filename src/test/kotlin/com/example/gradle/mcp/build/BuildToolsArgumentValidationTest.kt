package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class BuildToolsArgumentValidationTest {
    @Test
    fun `valid arguments pass through unchanged`() {
        val args = mapOf(
            "arguments" to listOf("--scan", "-Pfoo=bar"),
            "jvmArguments" to listOf("-Xmx2g"),
        )
        val (arguments, jvmArguments) = validatedUserBuildArguments(args)
        arguments shouldBe listOf("--scan", "-Pfoo=bar")
        jvmArguments shouldBe listOf("-Xmx2g")
    }

    @Test
    fun `omitted arguments default to empty lists`() {
        val (arguments, jvmArguments) = validatedUserBuildArguments(emptyMap())
        arguments shouldBe emptyList()
        jvmArguments shouldBe emptyList()
    }

    @Test
    fun `layout changing flags are rejected at admission`() {
        val cases = listOf(
            listOf("-g", "/tmp/evil"),
            listOf("--gradle-user-home", "/tmp/evil"),
            listOf("--gradle-user-home=/tmp/evil"),
            listOf("-p", "other-project"),
            listOf("--project-dir=other-project"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                validatedUserBuildArguments(mapOf("arguments" to arguments))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `init script flags are rejected at admission`() {
        val cases = listOf(
            listOf("-I", "evil.gradle"),
            listOf("--init-script", "evil.gradle"),
            listOf("--init-script=evil.gradle"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                validatedUserBuildArguments(mapOf("arguments" to arguments))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `mcp control project properties are rejected at admission`() {
        val exception = shouldThrow<McpException> {
            validatedUserBuildArguments(mapOf("arguments" to listOf("-Pmcp.recordDir=/tmp")))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `agent attaching jvm arguments are rejected at admission`() {
        val exception = shouldThrow<McpException> {
            validatedUserBuildArguments(mapOf("jvmArguments" to listOf("-javaagent:/tmp/evil.jar")))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `gradle user home in jvm arguments is rejected at admission`() {
        val exception = shouldThrow<McpException> {
            validatedUserBuildArguments(mapOf("jvmArguments" to listOf("-Dgradle.user.home=/tmp/evil")))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `validation result preserves argument order for the request`() {
        val args = mapOf("arguments" to listOf("--offline", "--continue"))
        validatedUserBuildArguments(args).first.shouldBeInstanceOf<List<String>>()
        validatedUserBuildArguments(args).first shouldBe listOf("--offline", "--continue")
    }
}
