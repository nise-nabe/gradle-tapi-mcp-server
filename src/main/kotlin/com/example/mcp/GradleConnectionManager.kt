package com.example.mcp

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.util.concurrent.Semaphore

class GradleConnectionManager {
    private val lock = Any()
    @Volatile
    private var operationPermit = Semaphore(1)
    private var connection: ProjectConnection? = null
    private var projectDirectory: File? = null

    fun connect(config: ConnectionConfig): ConnectionInfo = synchronized(lock) {
        disconnect()
        val projectDir = File(config.projectDirectory).absoluteFile
        require(projectDir.isDirectory) { "Project directory does not exist: ${projectDir.path}" }

        val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
        config.gradleInstallation?.let { connector.useInstallation(File(it).absoluteFile) }
        config.gradleVersion?.let { connector.useGradleVersion(it) }
        config.gradleUserHome?.let { connector.useGradleUserHomeDir(File(it).absoluteFile) }

        connection = connector.connect()
        projectDirectory = projectDir
        ConnectionInfo(projectDir.path, "connected")
    }

    fun withConnection(block: (ProjectConnection) -> Unit) {
        val permit = operationPermit
        if (!permit.tryAcquire()) {
            error("Another Gradle operation is in progress. Wait for it to finish or poll gradle_get_build_status.")
        }
        try {
            block(borrowConnection())
        } finally {
            permit.release()
        }
    }

    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T {
        val permit = operationPermit
        if (!permit.tryAcquire()) {
            error("Another Gradle operation is in progress. Wait for it to finish or poll gradle_get_build_status.")
        }
        try {
            return block(borrowConnection())
        } finally {
            permit.release()
        }
    }

    private fun borrowConnection(): ProjectConnection = synchronized(lock) {
        connection ?: error(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
        )
    }

    fun disconnect(): ConnectionInfo? = synchronized(lock) {
        val previous = projectDirectory?.path
        connection?.close()
        connection = null
        projectDirectory = null
        operationPermit = Semaphore(1)
        previous?.let { ConnectionInfo(it, "disconnected") }
    }

    fun status(): ConnectionStatus = synchronized(lock) {
        val dir = projectDirectory?.path
        ConnectionStatus(connected = connection != null, projectDirectory = dir)
    }

    fun tryAutoConnectFromEnvironment() {
        val projectDir = System.getenv("GRADLE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (status().connected) {
            return
        }
        connect(
            ConnectionConfig(
                projectDirectory = projectDir,
                gradleUserHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() },
                gradleVersion = System.getenv("GRADLE_VERSION")?.takeIf { it.isNotBlank() },
                gradleInstallation = System.getenv("GRADLE_INSTALLATION")?.takeIf { it.isNotBlank() },
            )
        )
    }

    internal fun seedConnectionForTests(connection: ProjectConnection, projectDirectory: File = File(".")) {
        synchronized(lock) {
            this.connection = connection
            this.projectDirectory = projectDirectory
        }
    }
}

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
)
