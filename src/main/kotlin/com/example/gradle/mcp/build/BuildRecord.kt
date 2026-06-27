package com.example.gradle.mcp.build

import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import java.time.Instant

data class BuildRecord(
    val id: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val testClasses: List<String>,
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
}
