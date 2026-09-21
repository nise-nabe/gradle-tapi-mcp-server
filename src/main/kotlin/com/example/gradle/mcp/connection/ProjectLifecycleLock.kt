package com.example.gradle.mcp.connection

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Per-project monitors serialize lifecycle operations (connect, disconnect, build start, model queries)
 * for the same Gradle root while allowing unrelated projects to proceed concurrently.
 * A global monitor covers disconnect-all and server shutdown.
 *
 * Entries are refcounted under [globalLock]: a lock is evicted once the last
 * [withProjectLock] call for its key exits, so [projectLocks] stays bounded by
 * the number of concurrently active projects rather than every project ever seen.
 */
internal object ProjectLifecycleLock {
    internal class CountedLock {
        var refs = 0
    }

    internal val projectLocks = ConcurrentHashMap<String, CountedLock>()
    internal val globalLock = Any()

    /**
     * Raw monitor for [directory], kept permanently by bumping its refcount.
     * Test seam for synchronizing with production code paths; production code
     * should use [withProjectLock] or [withLifecycleLock] so entries are released.
     */
    fun forProject(directory: File): Any =
        synchronized(globalLock) {
            projectLocks
                .getOrPut(ProjectDirectoryResolver.canonicalKey(directory)) { CountedLock() }
                .also { it.refs += 1 }
        }

    fun global(): Any = globalLock

    /**
     * Runs [block] under the monitor for [directory] and releases the entry
     * afterwards. Acquire (getOrPut + refcount) and release (decrement + evict)
     * both happen under [globalLock], so a monitor is never evicted while a
     * caller can still block on it.
     */
    @OptIn(ExperimentalContracts::class)
    inline fun <T> withProjectLock(directory: File, block: () -> T): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        val key = ProjectDirectoryResolver.canonicalKey(directory)
        val counted = synchronized(globalLock) {
            projectLocks.getOrPut(key) { CountedLock() }.also { it.refs += 1 }
        }
        try {
            return synchronized(counted, block)
        } finally {
            synchronized(globalLock) {
                counted.refs -= 1
                if (counted.refs == 0) {
                    projectLocks.remove(key, counted)
                }
            }
        }
    }

    /** [withProjectLock] for a resolved directory, [global] when it is absent. */
    @OptIn(ExperimentalContracts::class)
    inline fun <T> withLifecycleLock(projectDirectory: File?, block: () -> T): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return if (projectDirectory != null) {
            withProjectLock(projectDirectory, block)
        } else {
            synchronized(globalLock, block)
        }
    }
}
