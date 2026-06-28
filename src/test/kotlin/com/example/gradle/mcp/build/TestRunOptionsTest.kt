package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestRunOptionsTest {
    @Test
    fun `parseTestRunOptions accepts testClasses`() {
        val options = parseTestRunOptions(mapOf("testClasses" to listOf("com.example.FooTest")))
        options.testClasses shouldBe listOf("com.example.FooTest")
        options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest"))
    }

    @Test
    fun `toBuildRunRequest populates testClasses from testMethods keys for reporting`() {
        val request = TestRunOptions(
            selection = TestRunSelection.Methods(mapOf("com.example.FooTest" to listOf("method1"))),
        ).toBuildRunRequest(
            projectDirectory = testProjectDirectory,
            arguments = emptyList(),
            jvmArguments = emptyList(),
            outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
            progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
        )
        request.testClasses shouldBe listOf("com.example.FooTest")
    }

    @Test
    fun `validate rejects taskPath without classes or methods`() {
        val error = shouldThrow<McpException> {
            TestRunOptions(
                selection = TestRunSelection.Patterns(listOf("com.example.*")),
                tasks = listOf(":app:test"),
            ).validate(inputTaskPath = ":app:test")
        }
        error.message shouldBe "taskPath requires non-empty testClasses or testMethods"
    }

    @Test
    fun `describeTestOperation formats taskPath with testMethods`() {
        val description = describeTestOperation(
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Methods(
                    methods = mapOf("com.example.FooTest" to listOf("method1", "method2")),
                    taskPath = ":app:test",
                ),
            ),
        )
        description shouldBe "Gradle tests: :app:test methods com.example.FooTest#method1, method2"
    }

    @Test
    fun `validate rejects multiple selection mechanisms`() {
        val error = shouldThrow<McpException> {
            parseTestRunOptions(
                mapOf(
                    "testClasses" to listOf("com.example.FooTest"),
                    "testMethods" to mapOf("com.example.FooTest" to listOf("method1")),
                ),
            )
        }
        error.message shouldBe
            "Specify only one of testClasses, testMethods, or includePattern/includePatterns"
    }

    @Test
    fun `validate rejects includePatterns without tasks`() {
        val error = shouldThrow<McpException> {
            TestRunOptions(selection = TestRunSelection.Patterns(listOf("com.example.*"))).validate()
        }
        error.message shouldBe "includePattern/includePatterns requires tasks for test task scoping"
    }
}
