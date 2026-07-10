package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GradleConnectionManager {
    private data class PooledConnection(
        val projectDirectory: File,
        val connection: ProjectConnection,
        val cachedEnvironment: BuildEnvironmentSnapshot?,
        val cachedHasSubprojects: Boolean? = null,
        val config: ConnectionConfig,
    )

    private val pool = ConcurrentHashMap<String, PooledConnection>()

    fun ensureConnected(config: ConnectionConfig): ConnectionInfo {
        val projectDir = validateProjectDirectory(config.projectDirectory)
        val key = ProjectDirectoryResolver.canonicalKey(projectDir)
        val normalizedConfig = config.copy(projectDirectory = projectDir.path)

        pool[key]?.let { existing ->
            return existingConnectionInfo(existing, normalizedConfig, projectDir)
        }

        val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
        normalizedConfig.gradleInstallation?.let { connector.useInstallation(File(it).absoluteFile) }
        normalizedConfig.gradleVersion?.let { connector.useGradleVersion(it) }
        normalizedConfig.gradleUserHome?.let { connector.useGradleUserHomeDir(File(it).absoluteFile) }

        val newConnection = connector.connect()
        val snapshot = loadEnvironmentSnapshot(newConnection)
        val newPooled = PooledConnection(
            projectDirectory = projectDir,
            connection = newConnection,
            cachedEnvironment = snapshot,
            config = normalizedConfig,
        )

        synchronized(pool) {
            pool.putIfAbsent(key, newPooled)?.let { existing ->
                closeQuietly(newConnection)
                return existingConnectionInfo(existing, normalizedConfig, projectDir)
            }
        }
        return ConnectionInfo(projectDir.path, "connected")
    }

    fun connect(config: ConnectionConfig): ConnectionInfo = ensureConnected(config)

    fun requireConnection(projectDirectory: File): ProjectConnection = borrowConnection(projectDirectory)

    fun withConnection(projectDirectory: File, block: (ProjectConnection) -> Unit) {
        block(borrowConnection(projectDirectory))
    }

    fun <T> withConnectionResult(projectDirectory: File, block: (ProjectConnection) -> T): T =
        block(borrowConnection(projectDirectory))

    fun withConnection(block: (ProjectConnection) -> Unit) {
        withConnection(requireDefaultProjectDirectory(), block)
    }

    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T =
        withConnectionResult(requireDefaultProjectDirectory(), block)

    fun disconnect(projectDirectory: File? = null): ConnectionInfo? {
        if (projectDirectory == null) {
            return disconnectAll().lastOrNull()
        }
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val removed = synchronized(pool) {
            pool.remove(key)
        } ?: return null
        closeQuietly(removed.connection)
        return ConnectionInfo(removed.projectDirectory.path, "disconnected")
    }

    fun disconnectAll(): List<ConnectionInfo> {
        val removed = synchronized(pool) {
            val snapshot = pool.values.toList()
            pool.clear()
            snapshot
        }
        return removed.map { pooled ->
            closeQuietly(pooled.connection)
            ConnectionInfo(pooled.projectDirectory.path, "disconnected")
        }
    }

    fun connectedProjectDirectory(): File? = defaultProjectDirectory()

    fun defaultProjectDirectory(): File? {
        val workspace = ProjectDirectoryResolver.workspaceFromEnvironment()
        if (workspace != null) {
            if (pool.containsKey(ProjectDirectoryResolver.canonicalKey(workspace))) {
                return workspace
            }
            return null
        }
        if (pool.size == 1) {
            return pool.values.first().projectDirectory
        }
        return null
    }

    fun connectedProjectDirectories(): List<File> =
        pool.values.map { it.projectDirectory }.sortedBy { it.path }

    fun isConnected(projectDirectory: File): Boolean =
        pool.containsKey(ProjectDirectoryResolver.canonicalKey(projectDirectory))

    fun cachedEnvironment(projectDirectory: File): BuildEnvironmentSnapshot? =
        pool[ProjectDirectoryResolver.canonicalKey(projectDirectory)]?.cachedEnvironment

    fun cachedHasSubprojects(projectDirectory: File): Boolean? =
        pool[ProjectDirectoryResolver.canonicalKey(projectDirectory)]?.cachedHasSubprojects

    fun cacheHasSubprojects(projectDirectory: File, hasSubprojects: Boolean) {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        synchronized(pool) {
            val existing = pool[key] ?: return
            pool[key] = existing.copy(cachedHasSubprojects = hasSubprojects)
        }
    }

    fun cacheEnvironmentSnapshot(projectDirectory: File, snapshot: BuildEnvironmentSnapshot) {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        synchronized(pool) {
            val existing = pool[key] ?: return
            pool[key] = existing.copy(cachedEnvironment = snapshot)
        }
    }

    fun fetchAndCacheEnvironment(
        projectDirectory: File,
        connection: ProjectConnection,
    ): BuildEnvironmentSnapshot {
        val snapshot = requireBuildEnvironmentSnapshot(connection, projectDirectory)
        cacheEnvironmentSnapshot(projectDirectory, snapshot)
        return snapshot
    }

    fun refreshEnvironmentIfMissing(
        projectDirectory: File,
        connection: ProjectConnection,
    ): BuildEnvironmentSnapshot? =
        loadEnvironmentSnapshot(connection)?.also { cacheEnvironmentSnapshot(projectDirectory, it) }

    fun status(projectDirectory: File? = null, refresh: Boolean = false): Map<String, Any?> {
        if (projectDirectory != null) {
            return connectionStatus(projectDirectory, refresh).toResponseMap()
        }
        val default = defaultProjectDirectory()
        val connections = pool.values
            .map { pooled -> connectionStatus(pooled.projectDirectory, refresh) }
            .sortedBy { it.projectDirectory }
        return MultiConnectionStatus(
            defaultProjectDirectory = default?.path,
            connections = connections,
        ).toResponseMap()
    }

    fun tryAutoConnectFromEnvironment() {
        val projectDir = ProjectDirectoryResolver.workspaceFromEnvironment() ?: return
        tryAutoConnectFromDirectory(projectDir)
    }

    internal fun tryAutoConnectFromDirectory(projectDirectory: File) {
        if (isConnected(projectDirectory)) {
            return
        }
        try {
            connect(
                ConnectionConfig(
                    projectDirectory = projectDirectory.canonicalFile.path,
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

    internal fun seedConnectionForTests(
        connection: ProjectConnection,
        projectDirectory: File = File("."),
        environment: BuildEnvironmentSnapshot? = null,
        cachedHasSubprojects: Boolean? = null,
        config: ConnectionConfig? = null,
    ) {
        val canonical = projectDirectory.canonicalFile
        pool[ProjectDirectoryResolver.canonicalKey(canonical)] =
            PooledConnection(
                projectDirectory = canonical,
                connection = connection,
                cachedEnvironment = environment,
                cachedHasSubprojects = cachedHasSubprojects,
                config = config ?: ConnectionConfig(projectDirectory = canonical.path),
            )
    }

    private fun existingConnectionInfo(
        existing: PooledConnection,
        config: ConnectionConfig,
        projectDir: File,
    ): ConnectionInfo {
        if (!existing.config.hasSameConnectionSettings(config)) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Project ${projectDir.path} is already connected with different Gradle settings. " +
                    "Call gradle_disconnect first or use matching gradleUserHome, gradleVersion, " +
                    "and gradleInstallation.",
            )
        }
        return ConnectionInfo(projectDir.path, "connected")
    }

    private fun connectionStatus(projectDirectory: File, refresh: Boolean): ConnectionStatus {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val pooled = pool[key]
        val env = pooled?.cachedEnvironment
            ?: if (refresh) {
                pooled?.let { refreshEnvironmentIfMissing(it.projectDirectory, it.connection) }
            } else {
                null
            }
        return ConnectionStatus(
            connected = pooled != null,
            projectDirectory = projectDirectory.path,
            gradleVersion = env?.gradleVersion,
            versionInfo = env?.versionInfo,
            javaHome = env?.javaHome,
            javaVersion = env?.javaVersion,
            runtimeStackAvailable = env != null,
        )
    }

    private fun borrowConnection(projectDirectory: File): ProjectConnection {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val pooled = pool[key]
            ?: throw McpException(
                McpErrorCode.NOT_CONNECTED,
                "Not connected to Gradle project: ${projectDirectory.path}. Call gradle_connect first.",
            )
        return pooled.connection
    }

    private fun requireDefaultProjectDirectory(): File =
        defaultProjectDirectory()
            ?: throw McpException(
                McpErrorCode.NOT_CONNECTED,
                "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.",
            )

    private fun validateProjectDirectory(path: String): File {
        val projectDir = File(path).absoluteFile
        if (!projectDir.isDirectory) {
            throw McpException(
                McpErrorCode.PROJECT_NOT_FOUND,
                "Project directory does not exist: ${projectDir.path}",
            )
        }
        return projectDir.canonicalFile
    }

    private fun loadEnvironmentSnapshot(connection: ProjectConnection): BuildEnvironmentSnapshot? =
        try {
            buildEnvironmentSnapshotFrom(connection.getModel(BuildEnvironment::class.java))
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            null
        }

    private fun closeQuietly(connection: ProjectConnection) {
        try {
            connection.close()
        } catch (_: Exception) {
            // Best-effort close.
        }
    }
}
