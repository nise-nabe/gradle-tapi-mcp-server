package com.example.gradle.mcp.build.persistence

import kotlinx.serialization.Serializable

@Serializable
data class McpBuildLauncherMetadata(
    val buildId: String,
    val recordDir: String,
    val ccInitScript: String,
)
