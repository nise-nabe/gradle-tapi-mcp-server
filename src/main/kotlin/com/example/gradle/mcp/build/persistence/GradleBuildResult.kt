package com.example.gradle.mcp.build.persistence

import kotlinx.serialization.Serializable

@Serializable
data class GradleBuildResult(
    val schemaVersion: Int = 1,
    val buildId: String? = null,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val failure: String? = null,
    val taskNames: List<String> = emptyList(),
)
