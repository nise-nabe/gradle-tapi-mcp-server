package com.example.gradle.mcp.connection

data class ConnectionConfig(
    val projectDirectory: String,
    val gradleUserHome: String? = null,
    val gradleVersion: String? = null,
    val gradleInstallation: String? = null,
)

data class ConnectionInfo(
    val projectDirectory: String,
    val state: String,
)

data class ConnectionStatus(
    val connected: Boolean,
    val projectDirectory: String?,
    val gradleVersion: String? = null,
    val versionInfo: String? = null,
    val javaHome: String? = null,
    val javaVersion: String? = null,
    val runtimeStackAvailable: Boolean = false,
)
