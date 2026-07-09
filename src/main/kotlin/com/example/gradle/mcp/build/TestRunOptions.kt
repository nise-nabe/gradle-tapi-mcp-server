package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.optionalStringList
import com.example.gradle.mcp.protocol.testMethodsClassPropertyNames
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.TestLauncher
import org.gradle.tooling.model.GradleProject
import java.io.File

internal data class TestRunOptions(
    val selection: TestRunSelection? = null,
    val tasks: List<String> = emptyList(),
) {
    val testClasses: List<String> get() = selection.testClassesForReporting()
    val testMethods: Map<String, List<String>> get() = selection.testMethodsOrEmpty()
    val taskPath: String? get() = selection.taskPathOrNull()
    val includePatterns: List<String> get() = selection.includePatternsOrEmpty()
}

internal data class TestRunOptionsParseResult(
    val options: TestRunOptions,
    val selectionNormalized: Boolean = false,
)

internal fun parseTestRunOptions(args: Map<String, Any>): TestRunOptionsParseResult {
    val rawTestClasses = args.optionalStringList("testClasses").orEmpty().filter { it.isNotBlank() }.distinct()
    val testMethods = parseTestMethods(args)
    val normalized = normalizeTestClassEntries(rawTestClasses, testMethods)
    val taskPath = args.optionalString("taskPath")
    val includePatterns = buildList {
        args.optionalString("includePattern")?.takeIf { it.isNotBlank() }?.let { add(it) }
        args.optionalStringList("includePatterns")?.let { patterns -> addAll(patterns.filter { it.isNotBlank() }) }
    }.distinct()
    val tasks = args.optionalStringList("tasks").orEmpty().filter { it.isNotBlank() }.distinct()
    val selection = TestRunSelection.fromFlat(
        normalized.classes,
        normalized.methods,
        taskPath,
        includePatterns,
    )
    return TestRunOptionsParseResult(
        options = TestRunOptions(selection = selection, tasks = tasks),
        selectionNormalized = normalized.normalized,
    )
}

internal fun TestRunOptions.validate(inputTaskPath: String? = null): TestRunOptions {
    val selection = this.selection
        ?: throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "At least one of testClasses, testMethods, or includePattern/includePatterns must be provided",
        )
    val taskPathToCheck = inputTaskPath ?: selection.taskPath
    if (!taskPathToCheck.isNullOrBlank() && selection is TestRunSelection.Patterns) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "taskPath requires non-empty testClasses or testMethods",
        )
    }
    selection.validateWithTasks(tasks)
    return this
}

internal fun validateJvmTestProjectScope(
    connection: ProjectConnection,
    selection: TestRunSelection?,
    tasks: List<String>,
) {
    val unscoped = when (selection) {
        is TestRunSelection.Classes -> selection.taskPath.isNullOrBlank()
        is TestRunSelection.Methods -> selection.taskPath.isNullOrBlank()
        is TestRunSelection.Patterns, null -> false
    }
    if (!unscoped || tasks.isNotEmpty()) {
        return
    }

    val subprojectCount = countGradleSubprojects(connection.getModel(GradleProject::class.java))
    if (subprojectCount > 0) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "testClasses/testMethods without taskPath or tasks run matching tests in every subproject " +
                "($subprojectCount subprojects). Specify taskPath (e.g. \":module:test\") or tasks to scope execution.",
        )
    }
}

private fun countGradleSubprojects(project: GradleProject): Int {
    var count = 0
    fun visit(node: GradleProject) {
        for (child in node.children) {
            count++
            visit(child)
        }
    }
    visit(project)
    return count
}

internal fun TestRunOptions.toBuildRunRequest(
    projectDirectory: File,
    arguments: List<String>,
    jvmArguments: List<String>,
    outputLimit: OutputLimitOptions,
    progressOptions: ProgressResponseOptions,
): BuildRunRequest =
    BuildRunRequest(
        projectDirectory = projectDirectory,
        kind = BuildKind.TESTS,
        tasks = tasks,
        selection = selection,
        arguments = arguments,
        jvmArguments = jvmArguments,
        outputLimit = outputLimit,
        progressOptions = progressOptions,
    )

private fun formatTestMethodsLabel(testMethods: Map<String, List<String>>): String =
    testMethods.entries.joinToString("; ") { (className, methods) ->
        "$className#${methods.joinToString(", ")}"
    }

internal fun describeTestOperation(request: BuildRunRequest): String =
    buildString {
        append("Gradle tests: ")
        append(
            when (val selection = request.selection) {
                is TestRunSelection.Classes -> {
                    if (!selection.taskPath.isNullOrBlank()) {
                        "${selection.taskPath} classes ${selection.classes.joinToString()}"
                    } else {
                        selection.classes.joinToString()
                    }
                }
                is TestRunSelection.Methods -> {
                    if (!selection.taskPath.isNullOrBlank()) {
                        "${selection.taskPath} methods ${formatTestMethodsLabel(selection.methods)}"
                    } else {
                        formatTestMethodsLabel(selection.methods)
                    }
                }
                is TestRunSelection.Patterns -> "patterns ${selection.patterns.joinToString()}"
                null -> ""
            },
        )
        if (request.tasks.isNotEmpty()) {
            append(" (forTasks: ${request.tasks.joinToString()})")
        }
    }

internal fun configureTestLauncher(launcher: TestLauncher, request: BuildRunRequest): TestLauncher {
    var configured = launcher
    if (request.tasks.isNotEmpty()) {
        configured = configured.forTasks(*request.tasks.toTypedArray())
    }
    return when (val selection = request.selection) {
        is TestRunSelection.Classes -> {
            val taskPath = selection.taskPath
            if (!taskPath.isNullOrBlank()) {
                configured.withTaskAndTestClasses(taskPath, selection.classes)
            } else {
                configured.withJvmTestClasses(*selection.classes.toTypedArray())
            }
        }
        is TestRunSelection.Methods -> {
            val taskPath = selection.taskPath
            if (!taskPath.isNullOrBlank()) {
                selection.methods.entries.fold(configured) { acc, (className, methods) ->
                    acc.withTaskAndTestMethods(taskPath, className, methods)
                }
            } else {
                selection.methods.entries.fold(configured) { acc, (className, methods) ->
                    acc.withJvmTestMethods(className, methods)
                }
            }
        }
        is TestRunSelection.Patterns -> {
            configured.withTestsFor { specs ->
                for (path in request.tasks) {
                    specs.forTaskPath(path).includePatterns(selection.patterns)
                }
            }
        }
        null -> configured
    }
}

@Suppress("UNCHECKED_CAST")
private fun parseTestMethods(args: Map<String, Any>): Map<String, List<String>> {
    val raw = args["testMethods"] ?: return emptyMap()
    return when (raw) {
        is Map<*, *> -> parseTestMethodsMap(raw)
        is List<*> -> parseTestMethodsArray(raw)
        else -> throw invalidTestMethods(
            "testMethods must be an object map or array of entries with class/className/testClass and methods",
        )
    }.also { methods ->
        if (methods.values.any { it.isEmpty() }) {
            throw invalidTestMethods("testMethods entries must contain at least one method name")
        }
    }
}

private fun parseTestMethodsMap(raw: Map<*, *>): Map<String, List<String>> =
    raw.entries.associate { (key, value) ->
        val className = key as? String ?: throw invalidTestMethods("testMethods map keys must be strings")
        if (className.isBlank()) throw invalidTestMethods("testMethods map keys must be non-blank")
        className to parseMethodNameList(value, "testMethods")
    }

private fun parseTestMethodsArray(raw: List<*>): Map<String, List<String>> {
    val result = LinkedHashMap<String, MutableList<String>>()
    for (entry in raw) {
        val entryMap = entry as? Map<*, *>
            ?: throw invalidTestMethods(
                "testMethods array entries must be objects with class/className/testClass and methods",
            )
        val className = parseTestMethodsArrayClassName(entryMap)
        val methods = parseMethodNameList(entryMap["methods"], "methods")
        result.getOrPut(className) { mutableListOf() }.addAll(methods)
    }
    return result.mapValues { (_, methods) -> methods.distinct() }
}

private fun parseTestMethodsArrayClassName(entryMap: Map<*, *>): String {
    val presentKeys = testMethodsClassPropertyNames.filter { entryMap[it] != null }
    if (presentKeys.size > 1) {
        throw invalidTestMethods(
            "testMethods array entries must specify exactly one of class, className, or testClass",
        )
    }
    val key = presentKeys.singleOrNull()
        ?: throw invalidTestMethods("testMethods array entries require a class name")
    val className = entryMap[key] as? String
        ?: throw invalidTestMethods("testMethods array entries require a class name")
    if (className.isBlank()) {
        throw invalidTestMethods("testMethods array entries require a non-blank class name")
    }
    return className
}

private fun parseMethodNameList(value: Any?, field: String): List<String> {
    val list = value as? List<*> ?: throw invalidTestMethods("$field values must be string arrays")
    if (list.any { it !is String }) throw invalidTestMethods("$field values must contain only strings")
    return list.filterIsInstance<String>().filter { it.isNotBlank() }.distinct()
}

private fun invalidTestMethods(message: String): McpException =
    McpException(McpErrorCode.INVALID_ARGUMENT, message)

private data class NormalizedTestClassEntries(
    val classes: List<String>,
    val methods: Map<String, List<String>>,
    val normalized: Boolean,
)

private sealed interface ParsedTestClassEntry {
    data class ClassName(val name: String) : ParsedTestClassEntry

    data class MethodRef(val className: String, val methodName: String) : ParsedTestClassEntry
}

private fun normalizeTestClassEntries(
    testClasses: List<String>,
    existingMethods: Map<String, List<String>>,
): NormalizedTestClassEntries {
    if (existingMethods.isNotEmpty()) {
        // testClasses + testMethods together is rejected later by TestRunSelection.fromFlat.
        return NormalizedTestClassEntries(testClasses, existingMethods, normalized = false)
    }
    val classes = mutableListOf<String>()
    val methods = LinkedHashMap<String, MutableList<String>>()
    var normalized = false
    for (entry in testClasses) {
        when (val parsed = parseTestClassEntry(entry)) {
            is ParsedTestClassEntry.ClassName -> classes.add(parsed.name)
            is ParsedTestClassEntry.MethodRef -> {
                normalized = true
                methods.getOrPut(parsed.className) { mutableListOf() }.add(parsed.methodName)
            }
        }
    }
    if (classes.isNotEmpty() && methods.isNotEmpty()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "testClasses cannot mix fully qualified class names with Class.method entries; use testMethods for method selection",
        )
    }
    return NormalizedTestClassEntries(
        classes = classes,
        methods = methods.mapValues { (_, methodNames) -> methodNames.distinct() },
        normalized = normalized,
    )
}

private fun parseTestClassEntry(entry: String): ParsedTestClassEntry {
    val dotIndex = entry.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex >= entry.length - 1) {
        return ParsedTestClassEntry.ClassName(entry)
    }
    val className = entry.substring(0, dotIndex)
    val methodName = entry.substring(dotIndex + 1)
    // Wildcard patterns belong in includePatterns, not Class.method normalization.
    if (className.any { it == '*' || it == '?' } || methodName.any { it == '*' || it == '?' }) {
        return ParsedTestClassEntry.ClassName(entry)
    }
    // Lowercase leading segment is treated as a JVM method name (camelCase convention).
    if (methodName.first().isLowerCase()) {
        return ParsedTestClassEntry.MethodRef(className, methodName)
    }
    return ParsedTestClassEntry.ClassName(entry)
}
