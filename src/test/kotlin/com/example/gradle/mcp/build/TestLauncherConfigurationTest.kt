package com.example.gradle.mcp.build

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.gradle.tooling.TestLauncher
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

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

    @Test
    fun `configureTestLauncher prefers testMethods over includePatterns`() {
        val recording = recordingTestLauncher()

        configureTestLauncher(
            recording.launcher,
            BuildRunRequest(
                kind = BuildKind.TESTS,
                tasks = listOf(":app:test"),
                testMethods = mapOf("com.example.FooTest" to listOf("method1")),
                includePatterns = listOf("com.example.*"),
            ),
        )

        recording.calls shouldContainExactly listOf(
            TestLauncherCall("forTasks", listOf(":app:test")),
            TestLauncherCall("withJvmTestMethods", listOf("com.example.FooTest", listOf("method1"))),
        )
    }
}

private data class TestLauncherCall(
    val method: String,
    val args: List<Any?>,
)

private data class RecordedTestSpec(
    val taskPath: String,
    val patterns: List<String>,
)

private class RecordingTestLauncher(
    val launcher: TestLauncher,
    val calls: MutableList<TestLauncherCall>,
    val testsForSpecs: MutableList<RecordedTestSpec>,
)

private fun recordingTestLauncher(): RecordingTestLauncher {
    val calls = mutableListOf<TestLauncherCall>()
    val testsForSpecs = mutableListOf<RecordedTestSpec>()
    val self = arrayOfNulls<Any>(1)
    self[0] = Proxy.newProxyInstance(
        TestLauncher::class.java.classLoader,
        arrayOf(TestLauncher::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "withJvmTestClasses",
                "withJvmTestMethods",
                "withTaskAndTestClasses",
                "withTaskAndTestMethods",
                "forTasks",
                -> {
                    calls += TestLauncherCall(method.name, normalizeRecordedArgs(args))
                    self[0]
                }
                "withTestsFor" -> {
                    calls += TestLauncherCall(method.name, emptyList())
                    val action = args?.get(0) as org.gradle.api.Action<*>
                    val specs = recordingTestSpecs { taskPath ->
                        recordingTestSpec(taskPath, testsForSpecs)
                    }
                    @Suppress("UNCHECKED_CAST")
                    (action as org.gradle.api.Action<Any>).execute(specs)
                    self[0]
                }
                "run" -> null
                else -> self[0]
            }
        },
    )
    return RecordingTestLauncher(self[0] as TestLauncher, calls, testsForSpecs)
}

private fun normalizeRecordedArgs(args: Array<out Any?>?): List<Any?> {
    val raw = args?.toList().orEmpty()
    if (raw.size == 1 && raw[0] is Array<*>) {
        return (raw[0] as Array<*>).toList()
    }
    return raw.map { arg ->
        when (arg) {
            is Array<*> -> arg.toList()
            is Iterable<*> -> arg.toList()
            else -> arg
        }
    }
}

private fun recordingTestSpecs(
    specFactory: (String) -> Any,
): Any =
    Proxy.newProxyInstance(
        Class.forName("org.gradle.tooling.TestSpecs").classLoader,
        arrayOf(Class.forName("org.gradle.tooling.TestSpecs")),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "forTaskPath" -> specFactory(args?.get(0) as String)
                else -> null
            }
        },
    )

private fun recordingTestSpec(
    taskPath: String,
    sink: MutableList<RecordedTestSpec>,
): Any {
    val state = arrayOf(RecordedTestSpec(taskPath, emptyList()))
    val self = arrayOfNulls<Any>(1)
    self[0] = Proxy.newProxyInstance(
        Class.forName("org.gradle.tooling.TestSpec").classLoader,
        arrayOf(Class.forName("org.gradle.tooling.TestSpec")),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "includePatterns" -> {
                    val patterns = when (val value = args?.get(0)) {
                        is Collection<*> -> value.filterIsInstance<String>()
                        else -> emptyList()
                    }
                    state[0] = state[0].copy(patterns = patterns)
                    sink += state[0]
                    self[0]
                }
                else -> self[0]
            }
        },
    )
    return self[0]!!
}
