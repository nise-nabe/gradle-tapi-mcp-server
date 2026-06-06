package com.example.mcp

import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File
import java.time.Instant

data class BuildCacheStatusOptions(
    val includeLastMcpBuild: Boolean = true,
    val includeLocalCacheDetails: Boolean = true,
    val includeDeclaredProperties: Boolean = true,
    val probeConfigurationCache: Boolean = false,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): BuildCacheStatusOptions =
            BuildCacheStatusOptions(
                includeLastMcpBuild = readBooleanArg(args, "includeLastMcpBuild", default = true),
                includeLocalCacheDetails = readBooleanArg(args, "includeLocalCacheDetails", default = true),
                includeDeclaredProperties = readBooleanArg(args, "includeDeclaredProperties", default = true),
                probeConfigurationCache = readBooleanArg(args, "probeConfigurationCache", default = false),
            )

        private fun readBooleanArg(args: Map<String, Any>, key: String, default: Boolean): Boolean =
            when (val value = args[key]) {
                is Boolean -> value
                else -> default
            }
    }
}

data class LastMcpBuildInsight(
    val buildId: String,
    val kind: String,
    val tasks: List<String>,
    val finishedAt: String,
    val outcome: String,
    val taskSummaryLine: String?,
    val resultLine: String?,
    val taskStats: TaskExecutionStats?,
)

data class TaskExecutionStats(
    val actionableTasks: Int?,
    val executed: Int?,
    val fromCache: Int?,
    val upToDate: Int?,
)

object BuildCachePropertyKeys {
    val CACHE_KEYS = listOf(
        "org.gradle.caching",
        "org.gradle.caching.debug",
        "org.gradle.caching.local.directory",
        "org.gradle.caching.remote.url",
        "org.gradle.caching.remote.allowInsecureProtocol",
        "org.gradle.caching.remote.allowUntrustedServer",
        "org.gradle.configuration-cache",
        "org.gradle.configuration-cache.problems",
        "org.gradle.configuration-cache.max-problems",
        "org.gradle.unsafe.configuration-cache",
        "org.gradle.parallel",
    )

    fun isCacheRelated(key: String): Boolean =
        key in CACHE_KEYS ||
            key.startsWith("org.gradle.caching.") ||
            key.startsWith("org.gradle.configuration-cache.")
}

object GradlePropertiesParser {
    fun parse(text: String): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        for (line in OutputNormalizer.normalizeNewlines(text).lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }
            val separator = trimmed.indexOf('=').takeIf { it > 0 }
                ?: trimmed.indexOf(':').takeIf { it > 0 }
                ?: continue
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.isNotEmpty()) {
                properties[key] = value
            }
        }
        return properties
    }

    fun filterCacheRelated(properties: Map<String, String>): Map<String, String> =
        properties.filterKeys(BuildCachePropertyKeys::isCacheRelated)
}

object TaskExecutionStatsParser {
    private val actionableRegex = Regex("""(\d+) actionable tasks?""")
    private val executedRegex = Regex("""(\d+) executed""")
    private val fromCacheRegex = Regex("""(\d+) from cache""")
    private val upToDateRegex = Regex("""(\d+) up-to-date""")

    fun parse(taskSummaryLine: String?): TaskExecutionStats? {
        val line = taskSummaryLine?.trim().orEmpty()
        if (line.isEmpty()) {
            return null
        }
        val actionable = actionableRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val executed = executedRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val fromCache = fromCacheRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val upToDate = upToDateRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (actionable == null && executed == null && fromCache == null && upToDate == null) {
            return null
        }
        return TaskExecutionStats(
            actionableTasks = actionable,
            executed = executed,
            fromCache = fromCache,
            upToDate = upToDate,
        )
    }
}

object LocalGradleCacheInspector {
    private const val MAX_DIRECTORY_FILES = 5_000
    private const val MAX_CONFIGURATION_CACHE_STORES = 5

    fun inspect(
        gradleUserHome: File?,
        projectDirectory: File,
        includeDetails: Boolean,
    ): Map<String, Any?> {
        val local = linkedMapOf<String, Any?>()
        if (gradleUserHome != null) {
            val cachesDir = File(gradleUserHome, "caches")
            local["gradleUserHome"] = gradleUserHome.absolutePath
            local["buildCacheDirectories"] = listBuildCacheDirectories(cachesDir, includeDetails)
            local["configurationCacheStores"] = listConfigurationCacheStores(cachesDir, includeDetails)
        } else {
            local["gradleUserHome"] = null
        }

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
            ?.sortedBy { it["gradleVersionDir"] as String }
            ?.take(MAX_CONFIGURATION_CACHE_STORES)
            ?.toList()
            .orEmpty()
    }

    internal fun summarizeDirectoryForTests(root: File, maxFiles: Int): Map<String, Any?> =
        directorySummary(root, includeDetails = true, maxFiles = maxFiles)

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
        root.walkTopDown()
            .onEnter { fileCount < maxFiles }
            .forEach { file ->
                if (file.isFile) {
                    fileCount += 1
                    totalBytes += file.length()
                }
            }
        val capped = fileCount >= maxFiles
        return DirectoryStats(
            fileCount = if (capped) maxFiles else fileCount,
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

object BuildCacheStatusCollector {
    fun collect(
        connection: ProjectConnection,
        projectDirectory: File,
        options: BuildCacheStatusOptions,
        lastMcpBuild: LastMcpBuildInsight?,
    ): Map<String, Any?> {
        val environment = connection.getModel(BuildEnvironment::class.java)
        val gradleUserHome = environment.gradle.gradleUserHome

        val resolvedProperties = fetchResolvedProperties(connection)
        val cacheProperties = GradlePropertiesParser.filterCacheRelated(resolvedProperties)

        val declaredProperties = if (options.includeDeclaredProperties) {
            readDeclaredProperties(projectDirectory, gradleUserHome)
        } else {
            emptyMap()
        }

        val configurationCacheProbe = if (options.probeConfigurationCache) {
            probeConfigurationCache(connection)
        } else {
            null
        }

        return buildMap {
            put("projectDirectory", projectDirectory.absolutePath)
            put("summary", buildSummary(cacheProperties, declaredProperties, configurationCacheProbe))
            put("resolvedProperties", cacheProperties)
            if (options.includeDeclaredProperties) {
                put("declaredProperties", declaredProperties)
            }
            if (options.includeLocalCacheDetails) {
                put(
                    "local",
                    LocalGradleCacheInspector.inspect(
                        gradleUserHome = gradleUserHome,
                        projectDirectory = projectDirectory,
                        includeDetails = true,
                    ),
                )
            }
            if (options.includeLastMcpBuild) {
                put("lastMcpBuild", lastMcpBuild)
            }
            configurationCacheProbe?.let { put("configurationCacheProbe", it) }
        }
    }

    private fun buildSummary(
        resolved: Map<String, String>,
        declared: Map<String, Map<String, String>>,
        configurationCacheProbe: Map<String, Any?>?,
    ): Map<String, Any?> =
        buildMap {
            put("buildCacheEnabled", resolved.parseBoolean("org.gradle.caching"))
            put("remoteBuildCacheConfigured", !resolved["org.gradle.caching.remote.url"].isNullOrBlank())
            put("remoteBuildCacheUrl", resolved["org.gradle.caching.remote.url"])
            put("configurationCacheRequested", resolved.parseBoolean("org.gradle.configuration-cache")
                ?: resolved.parseBoolean("org.gradle.unsafe.configuration-cache"))
            put("configurationCacheProblems", resolved["org.gradle.configuration-cache.problems"])
            put("parallelExecution", resolved.parseBoolean("org.gradle.parallel"))
            put(
                "declaredInProjectFiles",
                listOfNotNull(declared["project"], declared["projectGradleDir"]).any { it.isNotEmpty() },
            )
            configurationCacheProbe?.let { probe ->
                put("configurationCacheProbeOutcome", probe["outcome"])
            }
        }

    private fun Map<String, String>.parseBoolean(key: String): Boolean? =
        this[key]?.trim()?.lowercase()?.let { value ->
            when (value) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> null
            }
        }

    private fun fetchResolvedProperties(connection: ProjectConnection): Map<String, String> {
        val streams = CapturingStreams(maxRetainedChars = 256_000)
        val launcher = connection.newBuild()
            .forTasks("properties")
            .addArguments("-q")
        streams.applyTo(launcher)
        launcher.run()
        return GradlePropertiesParser.parse(streams.stdoutText())
    }

    private fun readDeclaredProperties(
        projectDirectory: File,
        gradleUserHome: File?,
    ): Map<String, Map<String, String>> =
        linkedMapOf(
            "project" to readCachePropertiesFile(File(projectDirectory, "gradle.properties")),
            "projectGradleDir" to readCachePropertiesFile(File(projectDirectory, "gradle/gradle.properties")),
            "userHome" to gradleUserHome?.let { readCachePropertiesFile(File(it, "gradle.properties")) }.orEmpty(),
        )

    private fun readCachePropertiesFile(file: File): Map<String, String> {
        if (!file.isFile) {
            return emptyMap()
        }
        return GradlePropertiesParser.filterCacheRelated(
            GradlePropertiesParser.parse(file.readText()),
        )
    }

    private fun probeConfigurationCache(connection: ProjectConnection): Map<String, Any?> {
        val streams = CapturingStreams(maxRetainedChars = 4_000)
        return try {
            val launcher = connection.newBuild()
                .forTasks("properties")
                .addArguments("-q", "--configuration-cache")
            streams.applyTo(launcher)
            launcher.run()
            mapOf(
                "outcome" to "COMPATIBLE",
                "message" to "properties --configuration-cache completed successfully",
            )
        } catch (exception: Exception) {
            val stderr = streams.stderrText().trim().take(500)
            val stdout = streams.stdoutText().trim().take(500)
            mapOf(
                "outcome" to "INCOMPATIBLE",
                "message" to (exception.message ?: exception.toString()),
                "stderrExcerpt" to stderr.ifBlank { null },
                "stdoutExcerpt" to stdout.ifBlank { null },
            )
        }
    }
}

fun BuildExecutionManager.lastMcpBuildInsight(projectDirectory: File): LastMcpBuildInsight? {
    val snapshot = lastCompletedBuildSnapshot() ?: return null
    val snapshotProject = snapshot.projectDirectory ?: return null
    if (snapshotProject != projectDirectory.absolutePath) {
        return null
    }
    val summary = BuildOutputParser.parse(snapshot.stdout)
    return LastMcpBuildInsight(
        buildId = snapshot.buildId,
        kind = snapshot.kind.name.lowercase(),
        tasks = snapshot.tasks,
        finishedAt = snapshot.finishedAt.toString(),
        outcome = snapshot.outcome,
        taskSummaryLine = summary.taskSummaryLine,
        resultLine = summary.resultLine,
        taskStats = TaskExecutionStatsParser.parse(summary.taskSummaryLine),
    )
}

data class CompletedBuildSnapshot(
    val buildId: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val finishedAt: Instant,
    val outcome: String,
    val stdout: String,
    val projectDirectory: String?,
)
