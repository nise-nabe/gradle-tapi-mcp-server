package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.optionalStringList
import org.gradle.tooling.TestLauncher

internal data class TestRunOptions(
    val testClasses: List<String> = emptyList(),
    val testMethods: Map<String, List<String>> = emptyMap(),
    val taskPath: String? = null,
    val includePatterns: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
)

internal fun parseTestRunOptions(args: Map<String, Any>): TestRunOptions {
    val testClasses = args.optionalStringList("testClasses").orEmpty()
    val testMethods = parseTestMethods(args)
    val taskPath = args.optionalString("taskPath")
    val includePatterns = buildList {
        args.optionalString("includePattern")?.let { add(it) }
        args.optionalStringList("includePatterns")?.let { addAll(it) }
    }
    val tasks = args.optionalStringList("tasks").orEmpty()
    return TestRunOptions(
        testClasses = testClasses,
        testMethods = testMethods,
        taskPath = taskPath,
        includePatterns = includePatterns,
        tasks = tasks,
    )
}

internal fun TestRunOptions.validate(): TestRunOptions {
    val hasClasses = testClasses.isNotEmpty()
    val hasMethods = testMethods.isNotEmpty()
    val hasPatterns = includePatterns.isNotEmpty()

    if (!hasClasses && !hasMethods && !hasPatterns) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "At least one of testClasses, testMethods, or includePattern/includePatterns must be provided",
        )
    }

    if (!taskPath.isNullOrBlank() && !hasClasses && !hasMethods) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "taskPath requires non-empty testClasses or testMethods",
        )
    }

    if (hasPatterns && tasks.isEmpty()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "includePattern/includePatterns requires tasks for test task scoping",
        )
    }

    return this
}

internal fun TestRunOptions.toBuildRunRequest(
    arguments: List<String>,
    jvmArguments: List<String>,
    outputLimit: com.example.gradle.mcp.model.OutputLimitOptions,
    progressOptions: com.example.gradle.mcp.protocol.ProgressResponseOptions,
): BuildRunRequest =
    BuildRunRequest(
        kind = BuildKind.TESTS,
        tasks = tasks,
        testClasses = testClasses,
        testMethods = testMethods,
        taskPath = taskPath,
        includePatterns = includePatterns,
        arguments = arguments,
        jvmArguments = jvmArguments,
        outputLimit = outputLimit,
        progressOptions = progressOptions,
    )

internal fun describeTestOperation(request: BuildRunRequest): String =
    buildString {
        append("Gradle tests: ")
        append(
            when {
                !request.taskPath.isNullOrBlank() && request.testMethods.isNotEmpty() ->
                    "${request.taskPath} methods ${request.testMethods.entries.joinToString { "${it.key}#${it.value.joinToString()}" }}"
                !request.taskPath.isNullOrBlank() ->
                    "${request.taskPath} classes ${request.testClasses.joinToString()}"
                request.testMethods.isNotEmpty() ->
                    request.testMethods.entries.joinToString { "${it.key}#${it.value.joinToString()}" }
                request.includePatterns.isNotEmpty() ->
                    "patterns ${request.includePatterns.joinToString()}"
                else ->
                    request.testClasses.joinToString()
            },
        )
        if (request.tasks.isNotEmpty()) {
            append(" (forTasks: ${request.tasks.joinToString()})")
        }
    }

internal fun configureTestLauncher(
    launcher: TestLauncher,
    request: BuildRunRequest,
): TestLauncher {
    var configured = launcher
    if (request.tasks.isNotEmpty()) {
        configured = configured.forTasks(*request.tasks.toTypedArray())
    }

    val taskPath = request.taskPath
    return when {
        !taskPath.isNullOrBlank() -> {
            if (request.testMethods.isNotEmpty()) {
                request.testMethods.entries.fold(configured) { acc, (className, methods) ->
                    acc.withTaskAndTestMethods(taskPath, className, methods)
                }
            } else {
                configured.withTaskAndTestClasses(taskPath, request.testClasses)
            }
        }
        request.testMethods.isNotEmpty() -> {
            request.testMethods.entries.fold(configured) { acc, (className, methods) ->
                acc.withJvmTestMethods(className, methods)
            }
        }
        request.includePatterns.isNotEmpty() -> {
            configured.withTestsFor { specs ->
                for (path in request.tasks) {
                    specs.forTaskPath(path).includePatterns(request.includePatterns)
                }
            }
        }
        else -> {
            configured.withJvmTestClasses(*request.testClasses.toTypedArray())
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun parseTestMethods(args: Map<String, Any>): Map<String, List<String>> {
    val raw = args["testMethods"] ?: return emptyMap()
    return when (raw) {
        is Map<*, *> -> parseTestMethodsMap(raw)
        is List<*> -> parseTestMethodsArray(raw)
        else -> throw invalidTestMethods("testMethods must be an object map or array of {class, methods} entries")
    }.also { methods ->
        if (methods.values.any { it.isEmpty() }) {
            throw invalidTestMethods("testMethods entries must contain at least one method name")
        }
    }
}

private fun parseTestMethodsMap(raw: Map<*, *>): Map<String, List<String>> =
    raw.entries.associate { (key, value) ->
        val className = key as? String
            ?: throw invalidTestMethods("testMethods map keys must be strings")
        className to parseMethodNameList(value, "testMethods")
    }

private fun parseTestMethodsArray(raw: List<*>): Map<String, List<String>> {
    val result = LinkedHashMap<String, MutableList<String>>()
    for (entry in raw) {
        val entryMap = entry as? Map<*, *>
            ?: throw invalidTestMethods("testMethods array entries must be objects with class and methods")
        val className = (entryMap["class"] ?: entryMap["className"] ?: entryMap["testClass"]) as? String
            ?: throw invalidTestMethods("testMethods array entries require a class name")
        val methods = parseMethodNameList(entryMap["methods"], "testMethods")
        result.getOrPut(className) { mutableListOf() }.addAll(methods)
    }
    return result.mapValues { (_, methods) -> methods.distinct() }
}

private fun parseMethodNameList(value: Any?, field: String): List<String> {
    val list = value as? List<*>
        ?: throw invalidTestMethods("$field values must be string arrays")
    if (list.any { it !is String }) {
        throw invalidTestMethods("$field values must contain only strings")
    }
    return list.filterIsInstance<String>()
}

private fun invalidTestMethods(message: String): McpException =
    McpException(McpErrorCode.INVALID_ARGUMENT, message)
