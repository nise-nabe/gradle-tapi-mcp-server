package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.CapturedStreamSnapshot
import java.io.File

data class PersistedBuildArtifacts(
    val recordDir: File,
    val gradleResult: GradleBuildResult?,
    val mcpResult: McpBuildResult?,
    val stdout: CapturedStreamSnapshot,
    val stderr: CapturedStreamSnapshot,
    val events: List<DiskBuildEvent>,
)
