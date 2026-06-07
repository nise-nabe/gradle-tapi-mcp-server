package com.example.gradle.mcp.cache

import com.example.gradle.mcp.build.BuildKind
import java.time.Instant

data class CompletedBuildSnapshot(
    val buildId: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val testClasses: List<String>,
    val finishedAt: Instant,
    val outcome: String,
    val stdout: String,
    val projectDirectory: String?,
)
