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
    fun `parseTestRunOptions ignores blank includePatterns entries`() {
        val options = parseTestRunOptions(
            mapOf(
                "includePatterns" to listOf("", "  ", "com.example.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        options.includePatterns shouldBe listOf("com.example.*")
    }

    @Test
    fun `parseTestRunOptions ignores blank includePatterns when testClasses provided`() {
        val options = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("com.example.FooTest"),
                "includePatterns" to listOf(""),
            ),
        )

        options.testClasses shouldBe listOf("com.example.FooTest")
        options.includePatterns shouldBe emptyList()
    }

    @Test
    fun `toBuildRunRequest populates testClasses from testMethods keys for reporting`() {
        val request = TestRunOptions(
            testMethods = mapOf("com.example.FooTest" to listOf("method1")),
        ).toBuildRunRequest(
            arguments = emptyList(),
            jvmArguments = emptyList(),
            outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
            progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
        )

        request.testClasses shouldBe listOf("com.example.FooTest")
        request.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
    }

    @Test
    fun `parseTestRunOptions reports methods field in array form validation errors`() {
        val error = shouldThrow<McpException> {
            parseTestRunOptions(
                mapOf(
                    "testMethods" to listOf(
                        mapOf("class" to "com.example.FooTest", "methods" to listOf(1)),
                    ),
                ),
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe "methods values must contain only strings"
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
    fun `describeTestOperation formats taskPath with testMethods as class hash methods`() {
        val description = describeTestOperation(
            BuildRunRequest(
                kind = BuildKind.TESTS,
                testMethods = mapOf("com.example.FooTest" to listOf("method1", "method2")),
                taskPath = ":app:test",
            ),
        )

        description shouldBe "Gradle tests: :app:test methods com.example.FooTest#method1, method2"
    }

    @Test
    fun `describeTestOperation formats testMethods without taskPath as class hash methods`() {
        val description = describeTestOperation(
            BuildRunRequest(
                kind = BuildKind.TESTS,
                testMethods = mapOf("com.example.FooTest" to listOf("method1")),
            ),
        )

        description shouldBe "Gradle tests: com.example.FooTest#method1"
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
