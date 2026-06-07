package com.example.gradle.mcp.model

data class TaskSnapshot(
    val name: String,
    val path: String,
    val description: String?,
    val group: String?,
    val displayName: String,
)
