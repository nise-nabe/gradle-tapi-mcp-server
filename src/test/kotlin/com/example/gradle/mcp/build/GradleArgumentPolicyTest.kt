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
    fun `rejects combined mcp control project properties`() {
        val cases = listOf(
            "-Pmcp.launcherMetadata=/tmp/evil.json",
            "-Pmcp.ccInitScript=/tmp/evil.gradle",
            "-Pmcp.recordDir=/tmp",
            "-Pproject.mcp.launcherMetadata=/tmp/evil.json",
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlArguments(listOf(arguments))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects separated mcp control project properties`() {
        val cases = listOf(
            listOf("-P", "mcp.recordDir=/tmp"),
            listOf("-P", "project.mcp.launcherMetadata=/tmp/evil.json"),
            listOf("--project-prop", "mcp.ccInitScript=/tmp/evil.gradle"),
            listOf("--project-prop=mcp.recordDir=/tmp"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlArguments(arguments)
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects mcp control properties in jvmArguments`() {
        val cases = listOf(
            "-Dorg.gradle.project.mcp.recordDir=/tmp",
            "-Dorg.gradle.project.mcp.ccInitScript=/tmp/evil.gradle",
            "-Dorg.gradle.project.project.mcp.launcherMetadata=/tmp/evil.json",
        )
        for (argument in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlJvmArguments(listOf(argument))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `validateUserBuildArguments rejects jvmArguments bypass`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(
                arguments = emptyList(),
                jvmArguments = listOf("-Dorg.gradle.project.mcp.recordDir=/tmp"),
            )
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `allows regular gradle arguments`() {
        GradleArgumentPolicy.validateUserBuildArguments(
            arguments = listOf("--info", "-Dorg.gradle.parallel=true", "-Pfoo=bar", "-Parallel"),
            jvmArguments = listOf("-Xmx1g", "-Dorg.gradle.parallel=true"),
        )
    }
}
