package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.TestRunSelection
import com.example.gradle.mcp.protocol.DynamicMapSerializer
import kotlinx.serialization.Serializable

@Serializable
data class McpBuildResult(
    val schemaVersion: Int = 2,
    val buildId: String,
    val kind: String,
    val tasks: List<String>,
    val testClasses: List<String>,
    val testMethods: Map<String, List<String>> = emptyMap(),
    val taskPath: String? = null,
    val includePatterns: List<String> = emptyList(),
    val projectDirectory: String,
    val startedAt: String,
    val finishedAt: String,
    val status: String,
    val outcome: String? = null,
    val error: String? = null,
    val failureKind: String? = null,
    @Serializable(with = DynamicMapSerializer::class)
    val buildSummary: Map<String, Any?>? = null,
    val failedTaskCount: Int = 0,
    val failedTasks: List<String> = emptyList(),
    val failedGradleTaskCount: Int = 0,
    val failedGradleTasks: List<String> = emptyList(),
    val failedTestCount: Int = 0,
    val failedTestNames: List<String> = emptyList(),
    val problems: List<BuildProblemSnapshot> = emptyList(),
    val stdoutTotalChars: Int = 0,
    val stderrTotalChars: Int = 0,
) {
    val selection: TestRunSelection?
        get() = TestRunSelection.fromPersistedFlat(testClasses, testMethods, taskPath, includePatterns)
}
