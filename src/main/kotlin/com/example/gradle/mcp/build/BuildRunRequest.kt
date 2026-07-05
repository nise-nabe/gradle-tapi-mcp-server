package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import java.io.File

data class BuildRunRequest(
    val projectDirectory: File,
    val kind: BuildKind,
    val tasks: List<String> = emptyList(),
    val selection: TestRunSelection? = null,
    val arguments: List<String> = emptyList(),
    val jvmArguments: List<String> = emptyList(),
    val outputLimit: OutputLimitOptions = OutputLimitOptions(),
    val progressOptions: ProgressResponseOptions = ProgressResponseOptions(),
    val selectionNormalized: Boolean = false,
) {
    val testClasses: List<String> get() = selection.testClassesForReporting()
    val testMethods: Map<String, List<String>> get() = selection.testMethodsOrEmpty()
    val taskPath: String? get() = selection.taskPathOrNull()
    val includePatterns: List<String> get() = selection.includePatternsOrEmpty()
}
