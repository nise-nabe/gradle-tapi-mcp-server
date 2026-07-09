package com.example.gradle.mcp.connection

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project monitors serialize lifecycle operations (connect, disconnect, build start, model queries)
 * for the same Gradle root while allowing unrelated projects to proceed concurrently.
 * A global monitor covers disconnect-all and server shutdown.
 */
internal object ProjectLifecycleLock {
    private val projectLocks = ConcurrentHashMap<String, Any>()
    private val globalLock = Any()

    fun forProject(directory: File): Any =
        projectLocks.getOrPut(ProjectDirectoryResolver.canonicalKey(directory)) { Any() }

    fun global(): Any = globalLock
}
