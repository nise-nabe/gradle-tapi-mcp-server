package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.support.TestLauncherCall
import com.example.gradle.mcp.build.support.recordingTestLauncher
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestLauncherConfigurationTest {
    @Test
    fun `configureTestLauncher uses withJvmTestClasses by default`() {
        val recording = recordingTestLauncher()
        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Classes(listOf("com.example.FooTest")),
            ),
        )
        recording.calls shouldContainExactly listOf(
            TestLauncherCall("withJvmTestClasses", listOf("com.example.FooTest")),
        )
    }

    @Test
    fun `configureTestLauncher uses withJvmTestMethods`() {
        val recording = recordingTestLauncher()
        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Methods(mapOf("com.example.FooTest" to listOf("method1", "method2"))),
            ),
        )
        recording.calls shouldContainExactly listOf(
            TestLauncherCall("withJvmTestMethods", listOf("com.example.FooTest", listOf("method1", "method2"))),
        )
    }

    @Test
    fun `configureTestLauncher uses withTaskAndTestClasses when taskPath set`() {
        val recording = recordingTestLauncher()
        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Classes(listOf("com.example.FooTest"), taskPath = ":app:test"),
            ),
        )
        recording.calls shouldContainExactly listOf(
            TestLauncherCall("withTaskAndTestClasses", listOf(":app:test", listOf("com.example.FooTest"))),
        )
    }

    @Test
    fun `configureTestLauncher uses withTestsFor for includePatterns`() {
        val recording = recordingTestLauncher()
        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                tasks = listOf(":app:test"),
                selection = TestRunSelection.Patterns(listOf("com.example.*")),
            ),
        )
        recording.calls.map { it.method } shouldBe listOf("forTasks", "withTestsFor")
    }
}
