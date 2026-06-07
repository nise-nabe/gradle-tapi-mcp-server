package com.example.gradle.mcp.build

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
) {
    @Volatile
    var finishedAt: Instant? = null

    @Volatile
    var errorMessage: String? = null
}
