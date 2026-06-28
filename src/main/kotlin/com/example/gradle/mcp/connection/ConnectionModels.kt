package com.example.gradle.mcp.connection

import java.io.File

data class ConnectionConfig(
    val projectDirectory: String,
    val gradleUserHome: String? = null,
    val gradleVersion: String? = null,
    val gradleInstallation: String? = null,
) {
    fun hasSameConnectionSettings(other: ConnectionConfig): Boolean =
        normalizedGradleUserHome == other.normalizedGradleUserHome &&
            normalizedGradleVersion == other.normalizedGradleVersion &&
            normalizedGradleInstallation == other.normalizedGradleInstallation

    private val normalizedGradleUserHome: String?
        get() = gradleUserHome?.takeIf { it.isNotBlank() }

    private val normalizedGradleVersion: String?
        get() = gradleVersion?.takeIf { it.isNotBlank() }

    private val normalizedGradleInstallation: String?
        get() = gradleInstallation?.takeIf { it.isNotBlank() }
}

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
) {
    fun toResponseMap(): Map<String, Any?> = buildMap {
        put("connected", connected)
        put("projectDirectory", projectDirectory)
        put("gradleVersion", gradleVersion)
        put("versionInfo", versionInfo)
        put("javaHome", javaHome)
        put("javaVersion", javaVersion)
        put("runtimeStackAvailable", runtimeStackAvailable)
    }
}

data class MultiConnectionStatus(
    val defaultProjectDirectory: String?,
    val connections: List<ConnectionStatus>,
) {
    fun toResponseMap(): Map<String, Any?> {
        val ambiguousDefault = defaultProjectDirectory == null && connections.size > 1
        val default = when {
            ambiguousDefault -> null
            defaultProjectDirectory != null ->
                connections.firstOrNull { status ->
                    status.projectDirectory?.let { path ->
                        ProjectDirectoryResolver.canonicalKey(File(path)) ==
                            ProjectDirectoryResolver.canonicalKey(File(defaultProjectDirectory))
                    } == true
                }
            connections.size == 1 -> connections.first()
            else -> null
        }
        return buildMap {
            put("defaultProjectDirectory", defaultProjectDirectory)
            put("connections", connections.map { it.toResponseMap() })
            put("connectedAny", connections.any { it.connected })
            if (default != null) {
                putAll(default.toResponseMap())
            } else {
                put("connected", false)
                put("projectDirectory", defaultProjectDirectory)
                put("runtimeStackAvailable", false)
            }
        }
    }
}
