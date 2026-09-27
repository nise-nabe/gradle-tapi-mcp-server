package com.example.gradle.mcp.connection

import org.gradle.tooling.CancellationToken
import org.gradle.tooling.events.ProgressListener
import java.io.File

/**
 * Optional hooks for a single connect attempt. The Tooling API installs a
 * missing Gradle distribution lazily inside the first model call issued by
 * `gradle_connect`; [cancellationToken] lets a cancelled MCP request abort
 * that download, and [progressListener] receives its FILE_DOWNLOAD events.
 */
data class ConnectHooks(
    val cancellationToken: CancellationToken? = null,
    val progressListener: ProgressListener? = null,
)

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
        get() = gradleUserHome?.takeIf { it.isNotBlank() }?.let(::normalizePath)

    private val normalizedGradleVersion: String?
        get() = gradleVersion?.takeIf { it.isNotBlank() }

    private val normalizedGradleInstallation: String?
        get() = gradleInstallation?.takeIf { it.isNotBlank() }?.let(::normalizePath)

    private fun normalizePath(path: String): String =
        ProjectDirectoryResolver.canonicalKey(File(path))
}

data class ConnectionInfo(
    val projectDirectory: String,
    val state: String,
    val warning: String? = null,
    val reusedExistingConnection: Boolean = false,
    /** Disconnect result: the pooled connection stays alive for other sessions. */
    val retainedByOtherSessions: Boolean = false,
    /** Disconnect result: the pooled ProjectConnection was actually closed. */
    val closedPooledConnection: Boolean = false,
) {
    fun toResponseMap(): Map<String, Any?> = buildMap {
        put("projectDirectory", projectDirectory)
        put("state", state)
        warning?.let { put("warning", it) }
        if (reusedExistingConnection) {
            put("reusedExistingConnection", true)
        }
        if (retainedByOtherSessions) {
            put("retainedByOtherSessions", true)
        }
    }
}

data class ConnectionStatus(
    val connected: Boolean,
    val projectDirectory: String?,
    val connecting: Boolean = false,
    val gradleVersion: String? = null,
    val versionInfo: String? = null,
    val javaHome: String? = null,
    val javaVersion: String? = null,
    val runtimeStackAvailable: Boolean = false,
) {
    fun toResponseMap(): Map<String, Any?> = buildMap {
        put("connected", connected)
        put("connecting", connecting)
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
        val effectiveDefaultProjectDirectory = defaultProjectDirectory ?: default?.projectDirectory
        return buildMap {
            put("defaultProjectDirectory", effectiveDefaultProjectDirectory)
            put("connections", connections.map { it.toResponseMap() })
            put("connectedAny", connections.any { it.connected })
            if (default != null) {
                putAll(default.toResponseMap())
            } else {
                put("connected", false)
                put("projectDirectory", effectiveDefaultProjectDirectory)
                put("runtimeStackAvailable", false)
            }
        }
    }
}
