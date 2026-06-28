package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import java.io.File
import java.time.Instant

data class BuildRecord(
    val id: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val testClasses: List<String>,
    val testMethods: Map<String, List<String>> = emptyMap(),
    val taskPath: String? = null,
    val includePatterns: List<String> = emptyList(),
    val startedAt: Instant,
    val progressTracker: BuildProgressTracker,
    val streams: CapturingStreams,
    val projectDirectory: String? = null,
    val cancellationTokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource(),
) {
    @Volatile
    var finishedAt: Instant? = null

    @Volatile
    var errorMessage: String? = null

    fun matchesProject(projectDirectory: File?): Boolean =
        projectDirectory == null ||
            ProjectDirectoryResolver.sameProject(this.projectDirectory, projectDirectory)
}
