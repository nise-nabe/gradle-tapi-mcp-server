package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GradleConnectionManager(
    private val connectionOpener: (ConnectionConfig, File, ConnectHooks) -> Pair<ProjectConnection, BuildEnvironmentSnapshot?> =
        Companion::openConnection,
) {
    private data class PooledConnection(
        val projectDirectory: File,
        val connection: ProjectConnection,
        val cachedEnvironment: BuildEnvironmentSnapshot?,
        val config: ConnectionConfig,
    )

    private val pool = ConcurrentHashMap<String, PooledConnection>()

    /**
     * Canonical key -> sessions that hold the pooled connection. A pooled
     * connection is only physically closed when its last holder releases it,
     * so `gradle_disconnect` from one HTTP session cannot tear down a
     * connection another session still uses. Entries are mutated under
     * `synchronized(pool)`.
     */
    private val holders = ConcurrentHashMap<String, MutableSet<SessionProjectContext>>()

    /**
     * Incremented inside `synchronized(pool)` on every [disconnectAll]. A connect
     * that captured the epoch before connecting but reaches the pooling step after
     * a disconnect-all has completed must not be pooled: the pool would otherwise
     * retain a live connection after the caller observed "everything disconnected".
     */
    private var disconnectAllEpoch = 0L

    /**
     * Canonical keys with a connect currently in flight (a first-time Gradle
     * distribution download happens inside this window), so
     * [status] can report `connecting` instead of a misleading `connected: false`.
     */
    private val inflightConnects = ConcurrentHashMap<String, File>()

    fun ensureConnected(
        config: ConnectionConfig,
        hooks: ConnectHooks = ConnectHooks(),
        holder: SessionProjectContext? = null,
    ): ConnectionInfo {
        val projectDir = validateProjectDirectory(config.projectDirectory)
        val key = ProjectDirectoryResolver.canonicalKey(projectDir)
        val normalizedConfig = config.copy(projectDirectory = projectDir.path)

        val epochAtStart = synchronized(pool) {
            pool[key]?.let { existing ->
                holder?.let { registerHolderLocked(key, it) }
                return existingConnectionInfo(existing, normalizedConfig, projectDir, holder)
            }
            disconnectAllEpoch
        }

        inflightConnects[key] = projectDir
        try {
            val (newConnection, snapshot) = connectionOpener(normalizedConfig, projectDir, hooks)
            val newPooled = PooledConnection(
                projectDirectory = projectDir,
                connection = newConnection,
                cachedEnvironment = snapshot,
                config = normalizedConfig,
            )

            synchronized(pool) {
                if (disconnectAllEpoch != epochAtStart) {
                    closeQuietly(newConnection)
                    return ConnectionInfo(projectDir.path, "disconnected")
                }
                pool.putIfAbsent(key, newPooled)?.let { existing ->
                    closeQuietly(newConnection)
                    holder?.let { registerHolderLocked(key, it) }
                    return existingConnectionInfo(existing, normalizedConfig, projectDir, holder)
                }
                holder?.let { registerHolderLocked(key, it) }
            }
            return ConnectionInfo(projectDir.path, "connected")
        } finally {
            inflightConnects.remove(key)
        }
    }

    fun requireConnection(projectDirectory: File): ProjectConnection = borrowConnection(projectDirectory)

    fun <T> withConnectionResult(projectDirectory: File, block: (ProjectConnection) -> T): T =
        block(borrowConnection(projectDirectory))

    fun <T> withConnectionResult(block: (ProjectConnection) -> T): T =
        withConnectionResult(requireDefaultProjectDirectory(), block)

    /**
     * Releases [projectDirectory] (null = every project).
     *
     * With a [session], only that session's hold is released: the pooled
     * connection is closed just when no session holds it anymore, and the
     * result reports `retainedByOtherSessions`. Without a session the pooled
     * connection is closed unconditionally (legacy/test behaviour).
     */
    fun disconnect(
        projectDirectory: File? = null,
        session: SessionProjectContext? = null,
    ): ConnectionInfo? {
        if (projectDirectory == null) {
            return disconnectAll().lastOrNull()
        }
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        var retained = false
        val removed = synchronized(pool) {
            if (session != null) {
                val holderSet = holders[key]
                holderSet?.remove(session)
                if (holderSet.isNullOrEmpty()) {
                    holders.remove(key)
                    pool.remove(key)
                } else {
                    retained = true
                    null
                }
            } else {
                pool.remove(key)
            }
        }
        if (retained) {
            return ConnectionInfo(
                projectDirectory.path,
                "disconnected",
                retainedByOtherSessions = true,
            )
        }
        removed?.let { closeQuietly(it.connection) }
        return removed?.let {
            ConnectionInfo(it.projectDirectory.path, "disconnected", closedPooledConnection = true)
        }
    }

    /**
     * Whether [disconnect] on [directory] by [session] would physically close
     * the pooled connection — i.e. the pool has it and no other session holds
     * it. Lets callers cancel running builds before the close (like the old
     * unconditional disconnect) while leaving a shared connection untouched.
     */
    fun disconnectWouldClosePool(directory: File, session: SessionProjectContext?): Boolean {
        val key = ProjectDirectoryResolver.canonicalKey(directory)
        return synchronized(pool) {
            if (!pool.containsKey(key)) {
                return@synchronized false
            }
            if (session == null) {
                return@synchronized true
            }
            val holderSet = holders[key]
            holderSet.isNullOrEmpty() || (holderSet.size == 1 && session in holderSet)
        }
    }

    /**
     * Drops every hold of [session] (session teardown); pooled connections
     * left without holders are closed.
     */
    fun releaseSession(session: SessionProjectContext) {
        val toClose = mutableListOf<ProjectConnection>()
        synchronized(pool) {
            val iterator = holders.entries.iterator()
            while (iterator.hasNext()) {
                val (key, holderSet) = iterator.next()
                if (holderSet.remove(session) && holderSet.isEmpty()) {
                    iterator.remove()
                    pool.remove(key)?.let { toClose.add(it.connection) }
                }
            }
        }
        toClose.forEach { closeQuietly(it) }
    }

    /**
     * Registers [session] as a holder of its ambient workspace connection when
     * that connection already exists in the pool (e.g. environment
     * auto-connect finished before the session was created).
     */
    fun attachAmbientHolder(session: SessionProjectContext) {
        val workspace = session.workspaceProject() ?: return
        val key = ProjectDirectoryResolver.canonicalKey(workspace)
        synchronized(pool) {
            if (pool.containsKey(key)) {
                registerHolderLocked(key, session)
            }
        }
    }

    private fun registerHolderLocked(key: String, session: SessionProjectContext) {
        holders.getOrPut(key) { mutableSetOf() }.add(session)
    }

    fun disconnectAll(): List<ConnectionInfo> {
        val removed = synchronized(pool) {
            disconnectAllEpoch++
            val snapshot = pool.values.toList()
            pool.clear()
            holders.clear()
            snapshot
        }
        return removed.map { pooled ->
            closeQuietly(pooled.connection)
            ConnectionInfo(pooled.projectDirectory.path, "disconnected", closedPooledConnection = true)
        }
    }

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

    /**
     * Resolved Gradle user home for a connected project, or null when not connected.
     * Prefers the Tooling API [BuildEnvironment] snapshot, then the connect-time config override.
     */
    fun gradleUserHome(projectDirectory: File): File? {
        val pooled = pool[ProjectDirectoryResolver.canonicalKey(projectDirectory)] ?: return null
        pooled.cachedEnvironment?.gradleUserHome?.takeIf { it.isNotBlank() }?.let { return File(it) }
        pooled.config.gradleUserHome?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
        return null
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

    fun status(
        projectDirectory: File? = null,
        refresh: Boolean = false,
        isBuildActive: (File) -> Boolean = { false },
    ): Map<String, Any?> {
        if (projectDirectory != null) {
            return connectionStatus(projectDirectory, refresh, isBuildActive).toResponseMap()
        }
        val default = defaultProjectDirectory()
        val connectingOnly = inflightConnects.entries
            .filter { it.key !in pool.keys }
            .map { (_, dir) ->
                ConnectionStatus(connected = false, projectDirectory = dir.path, connecting = true)
            }
        val connections = (
            pool.values.map { pooled ->
                connectionStatus(pooled.projectDirectory, refresh, isBuildActive)
            } + connectingOnly
            ).sortedBy { it.projectDirectory }
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
            // The project lock serializes this background auto-connect with an
            // explicit gradle_connect for the same project, so a first-time
            // distribution download cannot run twice concurrently into the
            // same wrapper dists directory.
            ProjectLifecycleLock.withProjectLock(projectDirectory) {
                ensureConnected(
                    ConnectionConfig(
                        projectDirectory = projectDirectory.canonicalFile.path,
                        gradleUserHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() },
                        gradleVersion = System.getenv("GRADLE_VERSION")?.takeIf { it.isNotBlank() },
                        gradleInstallation = System.getenv("GRADLE_INSTALLATION")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // Auto-connect is best-effort at startup; the next gradle_connect reports the real error.
            System.err.println(
                "Auto-connect to ${projectDirectory.path} failed: ${exception.message}",
            )
        }
    }

    internal fun seedConnectionForTests(
        connection: ProjectConnection,
        projectDirectory: File = File("."),
        environment: BuildEnvironmentSnapshot? = null,
        config: ConnectionConfig? = null,
    ) {
        val canonical = projectDirectory.canonicalFile
        pool[ProjectDirectoryResolver.canonicalKey(canonical)] =
            PooledConnection(
                projectDirectory = canonical,
                connection = connection,
                cachedEnvironment = environment,
                config = config ?: ConnectionConfig(projectDirectory = canonical.path),
            )
    }

    /**
     * Reports the status of projects [session] knows (its ambient workspace
     * plus projects it connected), so a shared HTTP server does not leak
     * other sessions' project paths through `gradle_connection_status`.
     */
    fun statusForSession(
        session: SessionProjectContext,
        refresh: Boolean = false,
        isBuildActive: (File) -> Boolean = { false },
    ): Map<String, Any?> {
        val connections = session.knownProjects()
            .map { connectionStatus(it, refresh, isBuildActive) }
            .sortedBy { it.projectDirectory }
        return MultiConnectionStatus(
            defaultProjectDirectory = session.defaultProject()?.path,
            connections = connections,
        ).toResponseMap()
    }

    private fun existingConnectionInfo(
        existing: PooledConnection,
        config: ConnectionConfig,
        projectDir: File,
        session: SessionProjectContext? = null,
    ): ConnectionInfo {
        if (!existing.config.hasSameConnectionSettings(config)) {
            // A session joining a connection pooled by another session cannot
            // reconfigure it, but refusing outright would block the session
            // on a shared server — reuse it and report the ignored settings.
            // A session that already knows the project is its effective
            // owner, so a differing re-connect stays a hard error.
            if (session == null || session.isKnown(projectDir)) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Project ${projectDir.path} is already connected with different Gradle settings. " +
                        "Call gradle_disconnect first or use matching gradleUserHome, gradleVersion, " +
                        "and gradleInstallation.",
                )
            }
            return ConnectionInfo(
                projectDir.path,
                "connected",
                warning = "Project ${projectDir.path} is already connected with different Gradle " +
                    "settings; the existing connection was reused and the requested " +
                    "gradleUserHome/gradleVersion/gradleInstallation were not applied.",
                reusedExistingConnection = true,
            )
        }
        return ConnectionInfo(projectDir.path, "connected")
    }

    private fun connectionStatus(
        projectDirectory: File,
        refresh: Boolean,
        isBuildActive: (File) -> Boolean = { false },
    ): ConnectionStatus {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val pooled = pool[key]
        // Only connected projects can refresh; a refresh while a connect is
        // still in flight would otherwise block on the project lock for the
        // whole download.
        val env = pooled?.cachedEnvironment
            ?: if (pooled != null && refresh) {
                refreshEnvironmentWhenIdle(projectDirectory, isBuildActive)
            } else {
                null
            }
        return ConnectionStatus(
            connected = pooled != null,
            connecting = inflightConnects.containsKey(key),
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

    /**
     * Fetch BuildEnvironment only while the project has no active build.
     * The Tooling API connection is not thread-safe, so the fetch runs under
     * the per-project lifecycle lock: build starts take the same lock, which
     * serializes this getModel against them. A build that was already running
     * is reported by [isBuildActive] and the refresh is skipped.
     */
    private fun refreshEnvironmentWhenIdle(
        projectDirectory: File,
        isBuildActive: (File) -> Boolean,
    ): BuildEnvironmentSnapshot? =
        ProjectLifecycleLock.withProjectLock(projectDirectory) {
            val pooled = pool[ProjectDirectoryResolver.canonicalKey(projectDirectory)]
            if (pooled == null || isBuildActive(projectDirectory)) {
                null
            } else {
                refreshEnvironmentIfMissing(pooled.projectDirectory, pooled.connection)
            }
        }

    private fun closeQuietly(connection: ProjectConnection) {
        try {
            connection.close()
        } catch (_: Exception) {
            // Best-effort close.
        }
    }

    companion object {
        private fun openConnection(
            config: ConnectionConfig,
            projectDir: File,
            hooks: ConnectHooks,
        ): Pair<ProjectConnection, BuildEnvironmentSnapshot?> {
            val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
            config.gradleInstallation?.let { connector.useInstallation(File(it).absoluteFile) }
            config.gradleVersion?.let { connector.useGradleVersion(it) }
            config.gradleUserHome?.let { connector.useGradleUserHomeDir(File(it).absoluteFile) }
            val connection = connector.connect()
            // The Tooling API resolves (downloads) the Gradle distribution
            // lazily inside the first model call, so this fetch can block for
            // minutes on a cold GRADLE_USER_HOME or after a version bump.
            // Failures must propagate: pooling a connection that reports
            // "connected" but cannot run anything makes every later tool call
            // retry the same download.
            val snapshot = try {
                requireBuildEnvironmentSnapshot(connection, projectDir) {
                    hooks.cancellationToken?.let(::withCancellationToken)
                    hooks.progressListener?.let { listener ->
                        addProgressListener(listener, OperationType.FILE_DOWNLOAD)
                    }
                }
            } catch (exception: Exception) {
                if (exception is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                try {
                    connection.close()
                } catch (_: Exception) {
                    // Best-effort close.
                }
                when (exception) {
                    is McpException -> throw exception
                    is InterruptedException -> throw exception
                    else -> throw McpException(
                        McpErrorCode.INTERNAL_ERROR,
                        "Failed to initialize the Gradle connection for ${projectDir.path} " +
                            "(distribution install or daemon startup): ${exception.message}",
                        exception,
                    )
                }
            }
            return connection to snapshot
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
    }
}
