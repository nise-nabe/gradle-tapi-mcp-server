package com.example.mcp

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File

class GradleConnectionManager {
    private var connection: ProjectConnection? = null
    private var projectDirectory: File? = null
    private var cachedEnvironment: CachedBuildEnvironment? = null

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
        refreshEnvironmentCache()
        return ConnectionInfo(projectDir.path, "connected")
    }

    @Synchronized
    fun withConnection(block: (ProjectConnection) -> Unit) {
        block(requireConnection())
    }

    @Synchronized
    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T =
        block(requireConnection())

    private fun requireConnection(): ProjectConnection =
        connection ?: error(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
        )

    @Synchronized
    fun disconnect(): ConnectionInfo? {
        val previous = projectDirectory?.path
        connection?.close()
        connection = null
        projectDirectory = null
        cachedEnvironment = null
        return previous?.let { ConnectionInfo(it, "disconnected") }
    }

    @Synchronized
    fun status(): ConnectionStatus {
        if (connection != null && cachedEnvironment == null) {
            refreshEnvironmentCache()
        }
        val env = cachedEnvironment
        return ConnectionStatus(
            connected = connection != null,
            projectDirectory = projectDirectory?.path,
            gradleVersion = env?.gradleVersion,
            javaHome = env?.javaHome,
            javaVersion = env?.javaVersion,
        )
    }

    fun tryAutoConnectFromEnvironment() {
        val projectDir = System.getenv("GRADLE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (connection != null) {
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
        } catch (_: IllegalArgumentException) {
            // Auto-connect is best-effort at startup.
        }
    }

    private fun refreshEnvironmentCache() {
        val conn = connection ?: return
        try {
            val environment = conn.getModel(BuildEnvironment::class.java)
            cachedEnvironment = CachedBuildEnvironment.from(environment)
        } catch (_: Exception) {
            cachedEnvironment = null
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
    val gradleVersion: String? = null,
    val javaHome: String? = null,
    val javaVersion: String? = null,
)

data class CachedBuildEnvironment(
    val gradleVersion: String,
    val gradleUserHome: String?,
    val javaHome: String?,
    val javaVersion: String?,
) {
    companion object {
        fun from(environment: BuildEnvironment): CachedBuildEnvironment {
            val javaHome = environment.java.javaHome
            return CachedBuildEnvironment(
                gradleVersion = environment.gradle.gradleVersion,
                gradleUserHome = environment.gradle.gradleUserHome?.absolutePath,
                javaHome = javaHome?.absolutePath,
                javaVersion = JavaVersionResolver.resolve(javaHome),
            )
        }
    }
}
