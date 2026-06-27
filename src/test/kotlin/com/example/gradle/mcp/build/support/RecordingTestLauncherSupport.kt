package com.example.gradle.mcp.build.support

import org.gradle.tooling.TestLauncher
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal data class TestLauncherCall(
    val method: String,
    val args: List<Any?>,
)

internal data class RecordedTestSpec(
    val taskPath: String,
    val patterns: List<String>,
)

internal class RecordingTestLauncher(
    val launcher: TestLauncher,
    val calls: MutableList<TestLauncherCall>,
    val testsForSpecs: MutableList<RecordedTestSpec>,
)

internal fun recordingTestLauncher(): RecordingTestLauncher {
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
