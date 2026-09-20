package com.example.gradle.mcp.build.support

import com.example.gradle.mcp.support.selfReturningProxy
import org.gradle.tooling.TestLauncher
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal data class TestLauncherCall(
    val method: String,
    val args: List<Any?>,
)

internal data class RecordedTestSpec(
    val taskPath: String,
    val patterns: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val methods: Map<String, List<String>> = emptyMap(),
)

internal class RecordingTestLauncher(
    val launcher: TestLauncher,
    val calls: MutableList<TestLauncherCall>,
    val testsForSpecs: MutableList<RecordedTestSpec>,
)

internal fun recordingTestLauncher(): RecordingTestLauncher {
    val calls = mutableListOf<TestLauncherCall>()
    val testsForSpecs = mutableListOf<RecordedTestSpec>()
    val launcher = selfReturningProxy(TestLauncher::class.java) { self, method, args ->
        when (method.name) {
            "withJvmTestClasses",
            "withJvmTestMethods",
            "withTaskAndTestClasses",
            "withTaskAndTestMethods",
            "forTasks",
            -> {
                calls += TestLauncherCall(method.name, normalizeRecordedArgs(args))
                self
            }
            "withTestsFor" -> {
                calls += TestLauncherCall(method.name, emptyList())
                val action = args?.get(0) as org.gradle.api.Action<*>
                val specs = recordingTestSpecs { taskPath ->
                    recordingTestSpec(taskPath, testsForSpecs)
                }
                @Suppress("UNCHECKED_CAST")
                (action as org.gradle.api.Action<Any>).execute(specs)
                self
            }
            "run" -> null
            else -> self
        }
    } as TestLauncher
    return RecordingTestLauncher(launcher, calls, testsForSpecs)
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
    return selfReturningProxy(Class.forName("org.gradle.tooling.TestSpec")) { self, method, args ->
        when (method.name) {
            "includePatterns" -> {
                val patterns = when (val value = args?.get(0)) {
                    is Collection<*> -> value.filterIsInstance<String>()
                    else -> emptyList()
                }
                state[0] = state[0].copy(patterns = patterns)
                sink += state[0]
                self
            }
            "includeClasses" -> {
                val classes = when (val value = args?.get(0)) {
                    is Collection<*> -> value.filterIsInstance<String>()
                    else -> emptyList()
                }
                state[0] = state[0].copy(classes = classes)
                sink += state[0]
                self
            }
            "includeClass" -> {
                val className = args?.get(0) as? String ?: return@selfReturningProxy self
                state[0] = state[0].copy(classes = state[0].classes + className)
                sink += state[0]
                self
            }
            "includeMethods" -> {
                val className = args?.get(0) as? String ?: return@selfReturningProxy self
                val methods = when (val value = args.getOrNull(1)) {
                    is Collection<*> -> value.filterIsInstance<String>()
                    else -> emptyList()
                }
                state[0] = state[0].copy(methods = state[0].methods + (className to methods))
                sink += state[0]
                self
            }
            "includeMethod" -> {
                val className = args?.get(0) as? String ?: return@selfReturningProxy self
                val methodName = args.getOrNull(1) as? String ?: return@selfReturningProxy self
                val existing = state[0].methods[className].orEmpty()
                state[0] = state[0].copy(methods = state[0].methods + (className to (existing + methodName)))
                sink += state[0]
                self
            }
            else -> self
        }
    }
}
