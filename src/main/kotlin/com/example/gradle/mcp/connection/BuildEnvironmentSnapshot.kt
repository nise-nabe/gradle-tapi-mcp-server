package com.example.gradle.mcp.connection

data class BuildEnvironmentSnapshot(
    val gradleVersion: String,
    val gradleUserHome: String?,
    val javaHome: String?,
    val javaVersion: String?,
    val jvmArguments: List<String>,
)
