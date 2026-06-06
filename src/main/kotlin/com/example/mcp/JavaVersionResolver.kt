package com.example.mcp

import java.io.File

object JavaVersionResolver {
    fun resolve(javaHome: File?): String? {
        if (javaHome == null || !javaHome.isDirectory) {
            return null
        }
        val release = File(javaHome, "release")
        if (release.isFile) {
            return try {
                release.useLines { lines ->
                    lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.trim('"')
                        ?.takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}
