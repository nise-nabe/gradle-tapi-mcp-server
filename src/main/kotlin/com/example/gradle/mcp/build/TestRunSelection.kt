package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException

sealed interface TestRunSelection {
    val taskPath: String?

    data class Classes(
        val classes: List<String>,
        override val taskPath: String? = null,
    ) : TestRunSelection

    data class Methods(
        val methods: Map<String, List<String>>,
        override val taskPath: String? = null,
    ) : TestRunSelection

    data class Patterns(
        val patterns: List<String>,
    ) : TestRunSelection {
        override val taskPath: String? = null
    }

    companion object {
        fun fromFlat(
            testClasses: List<String>,
            testMethods: Map<String, List<String>>,
            taskPath: String?,
            includePatterns: List<String>,
        ): TestRunSelection? {
            val hasClasses = testClasses.isNotEmpty()
            val hasMethods = testMethods.isNotEmpty()
            val hasPatterns = includePatterns.isNotEmpty()

            val selectionModes = listOf(hasClasses, hasMethods, hasPatterns).count { it }
            if (selectionModes > 1) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Specify only one of testClasses, testMethods, or includePattern/includePatterns",
                )
            }

            return when {
                hasMethods -> Methods(testMethods, taskPath)
                hasPatterns -> Patterns(includePatterns)
                hasClasses -> Classes(testClasses, taskPath)
                else -> null
            }
        }

        fun fromPersistedFlat(
            testClasses: List<String>,
            testMethods: Map<String, List<String>>,
            taskPath: String?,
            includePatterns: List<String>,
        ): TestRunSelection? =
            when {
                testMethods.isNotEmpty() -> Methods(testMethods, taskPath)
                includePatterns.isNotEmpty() -> Patterns(includePatterns)
                testClasses.isNotEmpty() -> Classes(testClasses, taskPath)
                else -> null
            }
    }
}

internal fun TestRunSelection.validateWithTasks(tasks: List<String>): TestRunSelection {
    when (this) {
        is TestRunSelection.Classes, is TestRunSelection.Methods -> Unit
        is TestRunSelection.Patterns -> {
            if (tasks.isEmpty()) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "includePattern/includePatterns requires tasks for test task scoping",
                )
            }
        }
    }

    if (!taskPath.isNullOrBlank() && tasks.isNotEmpty() && taskPath !in tasks) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "tasks must include taskPath when both are specified",
        )
    }

    return this
}

internal fun TestRunSelection?.testClassesForReporting(): List<String> =
    when (this) {
        is TestRunSelection.Classes -> classes
        is TestRunSelection.Methods -> methods.keys.toList()
        is TestRunSelection.Patterns, null -> emptyList()
    }

internal fun TestRunSelection?.testMethodsOrEmpty(): Map<String, List<String>> =
    when (this) {
        is TestRunSelection.Methods -> methods
        else -> emptyMap()
    }

internal fun TestRunSelection?.taskPathOrNull(): String? =
    when (this) {
        is TestRunSelection.Classes -> taskPath
        is TestRunSelection.Methods -> taskPath
        is TestRunSelection.Patterns, null -> null
    }

internal fun TestRunSelection?.includePatternsOrEmpty(): List<String> =
    when (this) {
        is TestRunSelection.Patterns -> patterns
        else -> emptyList()
    }

internal fun MutableMap<String, Any?>.putTestRunSelection(selection: TestRunSelection?) {
    val testMethods = selection.testMethodsOrEmpty()
    if (testMethods.isNotEmpty()) {
        put("testMethods", testMethods)
    }
    selection.taskPathOrNull()?.takeIf { it.isNotBlank() }?.let { put("taskPath", it) }
    val includePatterns = selection.includePatternsOrEmpty()
    if (includePatterns.isNotEmpty()) {
        put("includePatterns", includePatterns)
    }
}
