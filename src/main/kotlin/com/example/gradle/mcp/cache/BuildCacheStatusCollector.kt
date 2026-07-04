package com.example.gradle.mcp.cache

import com.example.gradle.mcp.build.CapturingStreams
import com.example.gradle.mcp.build.GradlePropertiesStreamCapture
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

object BuildCacheStatusCollector {
    fun collect(
        connection: ProjectConnection,
        projectDirectory: File,
        options: BuildCacheStatusOptions,
        lastMcpBuild: LastMcpBuildInsight?,
    ): Map<String, Any?> {
        val gradleUserHome = if (options.includeLocalCacheDetails || options.includeDeclaredProperties) {
            connection.getModel(BuildEnvironment::class.java).gradle.gradleUserHome
        } else {
            null
        }

        val resolvedProperties = fetchResolvedProperties(connection)
        val cacheProperties = BuildCacheUrlRedactor.sanitizeCacheProperties(
            GradlePropertiesParser.filterCacheRelated(resolvedProperties),
        )

        val declaredProperties = readDeclaredProperties(
            projectDirectory = projectDirectory,
            gradleUserHome = if (options.includeDeclaredProperties) gradleUserHome else null,
        )

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
                put("lastMcpBuild", lastMcpBuild?.toResponseMap())
            }
            configurationCacheProbe?.let { put("configurationCacheProbe", it) }
        }
    }

    internal fun buildSummaryForTests(
        resolved: Map<String, String>,
        declared: Map<String, Map<String, String>>,
    ): Map<String, Any?> = buildSummary(resolved, declared, configurationCacheProbe = null)

    internal fun readDeclaredPropertiesForTests(
        projectDirectory: File,
        gradleUserHome: File?,
    ): Map<String, Map<String, String>> = readDeclaredProperties(projectDirectory, gradleUserHome)

    private fun buildSummary(
        resolved: Map<String, String>,
        declared: Map<String, Map<String, String>>,
        configurationCacheProbe: Map<String, Any?>?,
    ): Map<String, Any?> =
        buildMap {
            put("buildCacheEnabled", resolved.parseBoolean("org.gradle.caching"))
            put("remoteBuildCacheConfigured", !resolved["org.gradle.caching.remote.url"].isNullOrBlank())
            put(
                "remoteBuildCacheUrl",
                resolved["org.gradle.caching.remote.url"]?.let(BuildCacheUrlRedactor::redactUserInfo),
            )
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
        val capture = GradlePropertiesStreamCapture(retainKey = BuildCachePropertyKeys::isCacheRelated)
        val launcher = connection.newBuild()
            .forTasks("properties")
            .addArguments("-q")
        launcher.setStandardOutput(PrintStream(capture.asOutputStream(), true, StandardCharsets.UTF_8))
        launcher.setStandardError(PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8))
        launcher.run()
        return capture.snapshotProperties()
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
        return BuildCacheUrlRedactor.sanitizeCacheProperties(
            GradlePropertiesParser.filterCacheRelated(
                GradlePropertiesParser.parse(file.readText()),
            ),
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
