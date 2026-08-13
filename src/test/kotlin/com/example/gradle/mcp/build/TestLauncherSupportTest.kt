package com.example.gradle.mcp.build

import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestLauncherSupportTest {
    @Test
    fun `scopedTaskPaths prefers tasks list over taskPath`() {
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TESTS,
            tasks = listOf(":mod:test", ":mod:fastTest"),
            selection = TestRunSelection.Classes(listOf("com.example.FooTest"), taskPath = ":mod:test"),
        )

        TestLauncherSupport.scopedTaskPaths(request) shouldBe listOf(":mod:test", ":mod:fastTest")
    }

    @Test
    fun `scopedTaskPaths uses taskPath when tasks is empty`() {
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TESTS,
            selection = TestRunSelection.Methods(
                mapOf("com.example.FooTest" to listOf("works")),
                taskPath = ":plugin:test",
            ),
        )

        TestLauncherSupport.scopedTaskPaths(request) shouldBe listOf(":plugin:test")
    }

    @Test
    fun `isTaskLookupFailure matches wrapped TestLauncher missing-task message`() {
        val exception = RuntimeException(
            "Could not execute tests using connection to Gradle distribution.",
            RuntimeException("Requested test task with path ':plugin:test' cannot be found."),
        )

        TestLauncherSupport.isTaskLookupFailure(exception) shouldBe true
    }

    @Test
    fun `isTaskLookupFailure matches TestLauncher unsupported task type`() {
        val exception = RuntimeException(
            "Task ':plugin:test' of type 'org.gradle.api.DefaultTask' not supported for executing tests via TestLauncher API.",
        )

        TestLauncherSupport.isTaskLookupFailure(exception) shouldBe true
    }

    @Test
    fun `isTaskLookupFailure ignores unrelated failures`() {
        TestLauncherSupport.isTaskLookupFailure(RuntimeException("There were failing tests")) shouldBe false
    }

    @Test
    fun `shouldFallbackToBuildLauncher requires scoped path and no executed tasks`() {
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TESTS,
            selection = TestRunSelection.Classes(
                listOf("com.example.FooTest"),
                taskPath = ":plugin:test",
            ),
        )
        val lookup = RuntimeException("Requested test task with path ':plugin:test' cannot be found.")

        TestLauncherSupport.shouldFallbackToBuildLauncher(
            request = request,
            exception = lookup,
            completedTaskCount = 0,
            failedTasks = emptyList(),
        ) shouldBe true

        TestLauncherSupport.shouldFallbackToBuildLauncher(
            request = request,
            exception = lookup,
            completedTaskCount = 1,
            failedTasks = emptyList(),
        ) shouldBe false

        TestLauncherSupport.shouldFallbackToBuildLauncher(
            request = request.copy(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
            exception = lookup,
            completedTaskCount = 0,
            failedTasks = emptyList(),
        ) shouldBe false
    }

    @Test
    fun `testFilterCliArguments maps classes methods and patterns`() {
        TestLauncherSupport.testFilterCliArguments(
            TestRunSelection.Classes(listOf("com.example.FooTest", "com.example.BarTest")),
        ) shouldBe listOf("--tests", "com.example.FooTest", "--tests", "com.example.BarTest")

        TestLauncherSupport.testFilterCliArguments(
            TestRunSelection.Methods(
                mapOf(
                    "com.example.FooTest" to listOf("alpha", "beta"),
                ),
            ),
        ) shouldBe listOf("--tests", "com.example.FooTest.alpha", "--tests", "com.example.FooTest.beta")

        TestLauncherSupport.testFilterCliArguments(
            TestRunSelection.Patterns(listOf("com.example.*")),
        ) shouldBe listOf("--tests", "com.example.*")
    }
}
