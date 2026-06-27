package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestRunOptionsTest {
    @Test
    fun `parseTestRunOptions accepts testClasses`() {
        val options = parseTestRunOptions(mapOf("testClasses" to listOf("com.example.FooTest")))

        options.testClasses shouldBe listOf("com.example.FooTest")
    }

    @Test
    fun `parseTestRunOptions accepts testMethods map form`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("method1", "method2"),
                ),
            ),
        )

        options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1", "method2"))
    }

    @Test
    fun `parseTestRunOptions accepts testMethods array form`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to listOf(
                    mapOf("class" to "com.example.FooTest", "methods" to listOf("method1")),
                    mapOf("class" to "com.example.BarTest", "methods" to listOf("method2")),
                ),
            ),
        )

        options.testMethods shouldBe mapOf(
            "com.example.FooTest" to listOf("method1"),
            "com.example.BarTest" to listOf("method2"),
        )
    }

    @Test
    fun `parseTestRunOptions merges includePattern and includePatterns`() {
        val options = parseTestRunOptions(
            mapOf(
                "includePattern" to "com.example.*",
                "includePatterns" to listOf("com.other.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        options.includePatterns shouldBe listOf("com.example.*", "com.other.*")
        options.tasks shouldBe listOf(":app:test")
    }

    @Test
    fun `validate rejects missing selection mechanism`() {
        val error = shouldThrow<McpException> {
            TestRunOptions().validate()
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe
            "At least one of testClasses, testMethods, or includePattern/includePatterns must be provided"
    }

    @Test
    fun `validate rejects taskPath without classes or methods`() {
        val error = shouldThrow<McpException> {
            TestRunOptions(
                taskPath = ":app:test",
                includePatterns = listOf("com.example.*"),
                tasks = listOf(":app:test"),
            ).validate()
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe "taskPath requires non-empty testClasses or testMethods"
    }

    @Test
    fun `validate rejects includePatterns without tasks`() {
        val error = shouldThrow<McpException> {
            TestRunOptions(includePatterns = listOf("com.example.*")).validate()
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe "includePattern/includePatterns requires tasks for test task scoping"
    }
}
