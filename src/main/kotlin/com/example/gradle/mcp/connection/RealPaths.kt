package com.example.gradle.mcp.connection

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Canonical path with symlinks resolved. `File.getCanonicalFile` does not
 * follow links on Windows (unlike Unix realpath), so containment checks must
 * resolve the deepest existing ancestor with [Path.toRealPath] and re-append
 * the missing tail lexically.
 */
internal fun realPathResolvingSymlinks(file: File): Path {
    var cursor = file.toPath().toAbsolutePath().normalize()
    val missingTail = ArrayDeque<Path>()
    while (!Files.exists(cursor)) {
        val parent = cursor.parent ?: break
        missingTail.addFirst(cursor.fileName)
        cursor = parent
    }
    val resolved = runCatching { cursor.toRealPath() }.getOrDefault(cursor)
    return missingTail.fold(resolved) { acc, name -> acc.resolve(name) }
}
