package com.example.gradle.mcp.cache

import java.io.File

object LocalGradleCacheInspector {
    private const val MAX_DIRECTORY_FILES = 5_000
    private const val MAX_CONFIGURATION_CACHE_STORES = 5

    fun inspect(
        gradleUserHome: File?,
        projectDirectory: File,
        includeDetails: Boolean,
    ): Map<String, Any?> {
        val local = linkedMapOf<String, Any?>()
        local["gradleUserHome"] = gradleUserHome?.absolutePath
        val cachesDir = gradleUserHome?.let { File(it, "caches") }
        local["buildCacheDirectories"] = cachesDir?.let { listBuildCacheDirectories(it, includeDetails) }.orEmpty()
        local["configurationCacheStores"] = cachesDir?.let { listConfigurationCacheStores(it, includeDetails) }.orEmpty()

        val projectGradleDir = File(projectDirectory, ".gradle")
        local["projectGradleDirectory"] = projectGradleDir.absolutePath
        local["projectConfigurationCache"] = directorySummary(
            File(projectGradleDir, "configuration-cache"),
            includeDetails,
        )
        local["projectBuildCache"] = directorySummary(
            File(projectGradleDir, "build-cache"),
            includeDetails,
        )
        return local
    }

    private fun listBuildCacheDirectories(cachesDir: File, includeDetails: Boolean): List<Map<String, Any?>> {
        if (!cachesDir.isDirectory) {
            return emptyList()
        }
        return cachesDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("build-cache") }
            ?.sortedBy { it.name }
            ?.map { directorySummary(it, includeDetails) }
            ?.toList()
            .orEmpty()
    }

    private fun listConfigurationCacheStores(cachesDir: File, includeDetails: Boolean): List<Map<String, Any?>> {
        if (!cachesDir.isDirectory) {
            return emptyList()
        }
        return cachesDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { versionDir ->
                val ccDir = File(versionDir, "cc")
                if (ccDir.isDirectory) {
                    directorySummary(ccDir, includeDetails).toMutableMap().apply {
                        put("gradleVersionDir", versionDir.name)
                    }
                } else {
                    null
                }
            }
            ?.sortedWith { a, b ->
                compareGradleVersionDirs(
                    a["gradleVersionDir"] as String,
                    b["gradleVersionDir"] as String,
                )
            }
            ?.toList()
            ?.let { stores ->
                if (stores.size <= MAX_CONFIGURATION_CACHE_STORES) {
                    stores
                } else {
                    stores.takeLast(MAX_CONFIGURATION_CACHE_STORES)
                }
            }
            .orEmpty()
    }

    internal fun summarizeDirectoryForTests(root: File, maxFiles: Int): Map<String, Any?> =
        directorySummary(root, includeDetails = true, maxFiles = maxFiles)

    private fun parseGradleVersionParts(name: String): List<Int> {
        val match = Regex("""^(\d+(?:\.\d+)*)""").find(name) ?: return emptyList()
        return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
    }

    private fun compareGradleVersionDirs(a: String, b: String): Int {
        val aParts = parseGradleVersionParts(a)
        val bParts = parseGradleVersionParts(b)
        val maxLen = maxOf(aParts.size, bParts.size)
        for (index in 0 until maxLen) {
            val comparison = aParts.getOrElse(index) { 0 }.compareTo(bParts.getOrElse(index) { 0 })
            if (comparison != 0) {
                return comparison
            }
        }
        return a.compareTo(b)
    }

    private fun directorySummary(
        directory: File,
        includeDetails: Boolean,
        maxFiles: Int = MAX_DIRECTORY_FILES,
    ): Map<String, Any?> =
        buildMap {
            put("path", directory.absolutePath)
            put("exists", directory.isDirectory)
            if (!includeDetails || !directory.isDirectory) {
                return@buildMap
            }
            val stats = summarizeDirectory(directory, maxFiles)
            put("fileCount", stats.fileCount)
            put("totalBytes", stats.totalBytes)
            if (stats.capped) {
                put("fileCountCapped", true)
            }
        }

    private fun summarizeDirectory(root: File, maxFiles: Int = MAX_DIRECTORY_FILES): DirectoryStats {
        var fileCount = 0
        var totalBytes = 0L
        var capped = false
        for (file in root.walkTopDown()) {
            if (!file.isFile) {
                continue
            }
            if (fileCount >= maxFiles) {
                capped = true
                break
            }
            fileCount += 1
            totalBytes += file.length()
        }
        return DirectoryStats(
            fileCount = fileCount,
            totalBytes = totalBytes,
            capped = capped,
        )
    }

    private data class DirectoryStats(
        val fileCount: Int,
        val totalBytes: Long,
        val capped: Boolean,
    )
}
