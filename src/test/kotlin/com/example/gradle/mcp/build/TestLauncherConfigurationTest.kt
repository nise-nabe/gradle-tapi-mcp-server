package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.support.TestLauncherCall
import com.example.gradle.mcp.build.support.recordingTestLauncher
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
                kind = BuildKind.TESTS,
                testClasses = listOf("com.example.FooTest"),
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
                kind = BuildKind.TESTS,
                testMethods = mapOf("com.example.FooTest" to listOf("method1", "method2")),
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
                kind = BuildKind.TESTS,
                taskPath = ":app:test",
                testClasses = listOf("com.example.FooTest"),
            ),
        )

        recording.calls shouldContainExactly listOf(
            TestLauncherCall("withTaskAndTestClasses", listOf(":app:test", listOf("com.example.FooTest"))),
        )
    }

    @Test
    fun `configureTestLauncher uses withTaskAndTestMethods when taskPath and testMethods set`() {
        val recording = recordingTestLauncher()

        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                kind = BuildKind.TESTS,
                taskPath = ":app:test",
                testMethods = mapOf("com.example.FooTest" to listOf("method1")),
            ),
        )

        recording.calls shouldContainExactly listOf(
            TestLauncherCall("withTaskAndTestMethods", listOf(":app:test", "com.example.FooTest", listOf("method1"))),
        )
    }

    @Test
    fun `configureTestLauncher applies forTasks before selection`() {
        val recording = recordingTestLauncher()

        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                kind = BuildKind.TESTS,
                tasks = listOf(":app:test"),
                testClasses = listOf("com.example.FooTest"),
            ),
        )

        recording.calls shouldContainExactly listOf(
            TestLauncherCall("forTasks", listOf(":app:test")),
            TestLauncherCall("withJvmTestClasses", listOf("com.example.FooTest")),
        )
    }

    @Test
    fun `configureTestLauncher uses withTestsFor for includePatterns`() {
        val recording = recordingTestLauncher()

        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                kind = BuildKind.TESTS,
                tasks = listOf(":app:test"),
                includePatterns = listOf("com.example.*"),
            ),
        )

        recording.calls.map { it.method } shouldBe listOf("forTasks", "withTestsFor")
        recording.calls[0].args shouldBe listOf(":app:test")
        recording.testsForSpecs.single().taskPath shouldBe ":app:test"
        recording.testsForSpecs.single().patterns shouldBe listOf("com.example.*")
    }
}
