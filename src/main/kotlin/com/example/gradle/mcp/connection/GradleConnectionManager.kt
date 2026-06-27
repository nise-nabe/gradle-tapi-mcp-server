package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File

class GradleConnectionManager {
    private val lock = Any()
    private var connection: ProjectConnection? = null
    private var projectDirectory: File? = null
    private var cachedEnvironment: BuildEnvironmentSnapshot? = null

    fun connect(config: ConnectionConfig): ConnectionInfo {
        val projectDir = File(config.projectDirectory).absoluteFile
        if (!projectDir.isDirectory) {
            throw McpException(
                McpErrorCode.PROJECT_NOT_FOUND,
                "Project directory does not exist: ${projectDir.path}",
            )
        }

        disconnect()

        val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
        config.gradleInstallation?.let { connector.useInstallation(File(it).absoluteFile) }
        config.gradleVersion?.let { connector.useGradleVersion(it) }
        config.gradleUserHome?.let { connector.useGradleUserHomeDir(File(it).absoluteFile) }

        val newConnection = connector.connect()
        val snapshot = try {
            buildEnvironmentSnapshotFrom(newConnection.getModel(BuildEnvironment::class.java))
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            null
        }

        synchronized(lock) {
            val previousConnection = connection
            if (previousConnection != null && previousConnection !== newConnection) {
                previousConnection.close()
            }
            connection = newConnection
            projectDirectory = projectDir
            cachedEnvironment = snapshot
        }

        return ConnectionInfo(projectDir.path, "connected")
    }

    fun withConnection(block: (ProjectConnection) -> Unit) {
        block(borrowConnection())
    }

    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T =
        block(borrowConnection())

    private fun borrowConnection(): ProjectConnection = synchronized(lock) {
        connection ?: throw McpException(
            McpErrorCode.NOT_CONNECTED,
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.",
        )
    }

    fun disconnect(): ConnectionInfo? = synchronized(lock) {
        val previous = projectDirectory?.path
        connection?.close()
        connection = null
        projectDirectory = null
        cachedEnvironment = null
        previous?.let { ConnectionInfo(it, "disconnected") }
    }

    fun connectedProjectDirectory(): File? = synchronized(lock) {
        projectDirectory
    }

    fun status(): ConnectionStatus = synchronized(lock) {
        val env = cachedEnvironment
        ConnectionStatus(
            connected = connection != null,
            projectDirectory = projectDirectory?.path,
            gradleVersion = env?.gradleVersion,
            versionInfo = env?.versionInfo,
            javaHome = env?.javaHome,
            javaVersion = env?.javaVersion,
            runtimeStackAvailable = env != null,
        )
    }

    fun tryAutoConnectFromEnvironment() {
        val projectDir = System.getenv("GRADLE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (isConnected()) {
            return
        }
        try {
            connect(
                ConnectionConfig(
                    projectDirectory = projectDir,
                    gradleUserHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() },
                    gradleVersion = System.getenv("GRADLE_VERSION")?.takeIf { it.isNotBlank() },
                    gradleInstallation = System.getenv("GRADLE_INSTALLATION")?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // Auto-connect is best-effort at startup.
        }
    }

    private fun isConnected(): Boolean = synchronized(lock) {
        connection != null
    }

    internal fun seedConnectionForTests(
        connection: ProjectConnection,
        projectDirectory: File = File("."),
        environment: BuildEnvironmentSnapshot? = null,
    ) {
        synchronized(lock) {
            this.connection = connection
            this.projectDirectory = projectDirectory
            this.cachedEnvironment = environment
        }
    }
}
