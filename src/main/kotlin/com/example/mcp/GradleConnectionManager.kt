package com.example.mcp

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class GradleConnectionManager {
    private var connection: ProjectConnection? = null
    private var projectDirectory: File? = null

    @Synchronized
    fun connect(config: ConnectionConfig): ConnectionInfo {
        disconnect()
        val projectDir = File(config.projectDirectory).absoluteFile
        require(projectDir.isDirectory) { "Project directory does not exist: ${projectDir.path}" }

        val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
        config.gradleInstallation?.let { connector.useInstallation(File(it).absoluteFile) }
        config.gradleVersion?.let { connector.useGradleVersion(it) }
        config.gradleUserHome?.let { connector.useGradleUserHomeDir(File(it).absoluteFile) }

        connection = connector.connect()
        projectDirectory = projectDir
        return ConnectionInfo(projectDir.path, "connected")
    }

    @Synchronized
    fun withConnection(block: (ProjectConnection) -> Unit) {
        val active = connection ?: error(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
        )
        block(active)
    }

    @Synchronized
    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T {
        val active = connection ?: error(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
        )
        return block(active)
    }

    @Synchronized
    fun disconnect(): ConnectionInfo? {
        val previous = projectDirectory?.path
        connection?.close()
        connection = null
        projectDirectory = null
        return previous?.let { ConnectionInfo(it, "disconnected") }
    }

    @Synchronized
    fun status(): ConnectionStatus {
        val dir = projectDirectory?.path
        return ConnectionStatus(connected = connection != null, projectDirectory = dir)
    }

    fun tryAutoConnectFromEnvironment() {
        val projectDir = System.getenv("GRADLE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (connection != null) {
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

class CapturingStreams {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    fun stdoutText(): String = stdout.toString(StandardCharsets.UTF_8)
    fun stderrText(): String = stderr.toString(StandardCharsets.UTF_8)

    fun applyTo(launcher: org.gradle.tooling.ConfigurableLauncher<*>) {
        launcher.setStandardOutput(PrintStream(stdout, true, StandardCharsets.UTF_8))
        launcher.setStandardError(PrintStream(stderr, true, StandardCharsets.UTF_8))
    }
}
