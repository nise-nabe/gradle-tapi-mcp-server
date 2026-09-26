package com.example.gradle.mcp.connection

import java.io.File

/**
 * Per-MCP-session view of "which Gradle projects this session works with".
 *
 * stdio serves one session per process, so this degenerates to the
 * GRADLE_PROJECT_DIR behaviour. Streamable HTTP serves many sessions from one
 * process; [knownProjects] is the session boundary used for default-project
 * resolution, status/list filtering, and cross-session isolation.
 *
 * Membership has two sources:
 * - the ambient workspace directory (GRADLE_PROJECT_DIR or an explicit seed
 *   such as the X-Gradle-Project-Dir request header), and
 * - projects the session connected via `gradle_connect` ([onConnected]).
 *
 * Holding a project here is tracked in [GradleConnectionManager.holders] too:
 * the pooled connection is only physically closed when the last session
 * releases it.
 */
class SessionProjectContext(
    workspaceProject: File? = ProjectDirectoryResolver.workspaceFromEnvironment(),
    seedProjectDirectories: List<File> = emptyList(),
) {
    private val lock = Any()

    /** Ambient project visible to every session; always counts as known. */
    private val workspace: File? = workspaceProject

    /** Canonical key -> directory for projects this session connected/seeded. */
    private val held = LinkedHashMap<String, File>()

    /** Default for calls without projectDirectory: last connect, else workspace. */
    private var defaultKey: String? = null

    init {
        workspace?.let { defaultKey = ProjectDirectoryResolver.canonicalKey(it) }
        seedProjectDirectories.forEach { directory ->
            val key = ProjectDirectoryResolver.canonicalKey(directory)
            held[key] = directory
            defaultKey = key
        }
    }

    fun workspaceProject(): File? = workspace

    fun defaultProject(): File? = synchronized(lock) {
        val key = defaultKey ?: return@synchronized null
        if (workspace != null && ProjectDirectoryResolver.canonicalKey(workspace) == key) {
            workspace
        } else {
            held[key]
        }
    }

    fun isKnown(directory: File): Boolean = synchronized(lock) {
        val key = ProjectDirectoryResolver.canonicalKey(directory)
        key in held || (workspace != null && ProjectDirectoryResolver.canonicalKey(workspace) == key)
    }

    /** Workspace plus every project this session connected, for scoping. */
    fun knownProjects(): List<File> = synchronized(lock) {
        listOfNotNull(workspace) + held.values
    }

    fun onConnected(directory: File): Unit = synchronized(lock) {
        val key = ProjectDirectoryResolver.canonicalKey(directory)
        held[key] = directory
        defaultKey = key
    }

    /**
     * Releases one project (null = all held). The default falls back to the
     * workspace when the default project is released.
     */
    fun onDisconnected(directory: File?): Unit = synchronized(lock) {
        if (directory == null) {
            held.clear()
            defaultKey = workspace?.let(ProjectDirectoryResolver::canonicalKey)
            return
        }
        val key = ProjectDirectoryResolver.canonicalKey(directory)
        held.remove(key)
        if (defaultKey == key) {
            defaultKey = workspace?.let(ProjectDirectoryResolver::canonicalKey)
        }
    }
}
