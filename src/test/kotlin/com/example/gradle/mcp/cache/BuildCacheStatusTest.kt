package com.example.gradle.mcp.cache

import com.example.gradle.mcp.build.GradlePropertiesStreamCapture
import com.example.gradle.mcp.protocol.decodeMcpJsonMap
import com.example.gradle.mcp.protocol.encodeMcpJsonDynamic
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class BuildCacheStatusTest {
    @Test
    fun `GradlePropertiesParser parses colon and equals separators`() {
        val properties = GradlePropertiesParser.parse(
            """
            # comment
            org.gradle.caching: true
            org.gradle.caching.remote.url=https://cache.example.com
            org.gradle.parallel=false
            """.trimIndent(),
        )

        properties["org.gradle.caching"] shouldBe "true"
        properties["org.gradle.caching.remote.url"] shouldBe "https://cache.example.com"
        properties["org.gradle.parallel"] shouldBe "false"
    }

    @Test
    fun `GradlePropertiesParser keeps only cache related keys`() {
        val filtered = GradlePropertiesParser.filterCacheRelated(
            mapOf(
                "version" to "1.0",
                "org.gradle.caching" to "true",
                "org.gradle.configuration-cache.problems" to "warn",
            ),
        )

        filtered.size shouldBe 2
        filtered shouldNotContainKey "version"
    }

    @Test
    fun `TaskExecutionStatsParser extracts executed and from cache counts`() {
        val stats = TaskExecutionStatsParser.parse("12 actionable tasks: 10 executed, 2 from cache")

        requireNotNull(stats)
        stats.actionableTasks shouldBe 12
        stats.executed shouldBe 10
        stats.fromCache shouldBe 2
        stats.upToDate.shouldBeNull()
    }

    @Test
    fun `TaskExecutionStatsParser extracts up-to-date counts`() {
        val stats = TaskExecutionStatsParser.parse("5 actionable tasks: 5 up-to-date")

        requireNotNull(stats)
        stats.actionableTasks shouldBe 5
        stats.upToDate shouldBe 5
        stats.executed.shouldBeNull()
    }

    @Test
    fun `LocalGradleCacheInspector summarizes build cache directories`(@TempDir tempDir: File) {
        val cachesDir = File(tempDir, "caches").apply { mkdirs() }
        val buildCacheDir = File(cachesDir, "build-cache-1").apply { mkdirs() }
        File(buildCacheDir, "ab/cd/entry.bin").apply {
            parentFile.mkdirs()
            writeText("cached")
        }

        val inspected = LocalGradleCacheInspector.inspect(
            gradleUserHome = tempDir,
            projectDirectory = tempDir,
            includeDetails = true,
        )

        @Suppress("UNCHECKED_CAST")
        val buildCacheDirectories = inspected["buildCacheDirectories"] as List<Map<String, Any?>>
        buildCacheDirectories.size shouldBe 1
        buildCacheDirectories.single()["exists"] shouldBe true
        buildCacheDirectories.single()["fileCount"] shouldBe 1
        buildCacheDirectories.single()["fileCountCapped"].shouldBeNull()
    }

    @Test
    fun `directorySummary exposes fileCountCapped when walk limit is reached`(@TempDir tempDir: File) {
        repeat(3) { index ->
            File(tempDir, "file$index.txt").writeText("x")
        }

        val summary = LocalGradleCacheInspector.summarizeDirectoryForTests(tempDir, maxFiles = 2)

        summary["fileCount"] shouldBe 2
        summary["fileCountCapped"] shouldBe true
    }

    @Test
    fun `directorySummary does not cap when file count equals limit exactly`(@TempDir tempDir: File) {
        repeat(2) { index ->
            File(tempDir, "file$index.txt").writeText("x")
        }

        val summary = LocalGradleCacheInspector.summarizeDirectoryForTests(tempDir, maxFiles = 2)

        summary["fileCount"] shouldBe 2
        summary["fileCountCapped"].shouldBeNull()
    }

    @Test
    fun `LocalGradleCacheInspector keeps newest configuration cache stores`(@TempDir tempDir: File) {
        val cachesDir = File(tempDir, "caches").apply { mkdirs() }
        listOf("7.0", "8.0", "8.14", "9.0", "9.1", "10.0").forEach { version ->
            File(cachesDir, "$version/cc").mkdirs()
        }

        val inspected = LocalGradleCacheInspector.inspect(
            gradleUserHome = tempDir,
            projectDirectory = tempDir,
            includeDetails = false,
        )

        @Suppress("UNCHECKED_CAST")
        val stores = inspected["configurationCacheStores"] as List<Map<String, Any?>>
        stores.map { it["gradleVersionDir"] } shouldBe listOf("8.0", "8.14", "9.0", "9.1", "10.0")
    }

    @Test
    fun `LocalGradleCacheInspector lists configuration cache stores in version order`(@TempDir tempDir: File) {
        val cachesDir = File(tempDir, "caches").apply { mkdirs() }
        File(cachesDir, "9.0/cc").apply { mkdirs() }
        File(cachesDir, "8.14/cc").apply { mkdirs() }
        File(cachesDir, "10.0/cc").apply { mkdirs() }

        val inspected = LocalGradleCacheInspector.inspect(
            gradleUserHome = tempDir,
            projectDirectory = tempDir,
            includeDetails = false,
        )

        @Suppress("UNCHECKED_CAST")
        val stores = inspected["configurationCacheStores"] as List<Map<String, Any?>>
        stores.map { it["gradleVersionDir"] } shouldBe listOf("8.14", "9.0", "10.0")
    }

    @Test
    fun `LocalGradleCacheInspector includes empty cache lists when gradle user home is missing`(@TempDir tempDir: File) {
        val inspected = LocalGradleCacheInspector.inspect(
            gradleUserHome = null,
            projectDirectory = tempDir,
            includeDetails = false,
        )

        @Suppress("UNCHECKED_CAST")
        inspected["buildCacheDirectories"] shouldBe emptyList<Map<String, Any?>>()
        @Suppress("UNCHECKED_CAST")
        inspected["configurationCacheStores"] shouldBe emptyList<Map<String, Any?>>()
        inspected["gradleUserHome"].shouldBeNull()
    }

    @Test
    fun `summary declaredInProjectFiles reflects project gradle properties`(@TempDir tempDir: File) {
        File(tempDir, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(tempDir, null)
        val summary = BuildCacheStatusCollector.buildSummaryForTests(emptyMap(), declared)

        summary["declaredInProjectFiles"] shouldBe true
    }

    @Test
    fun `readDeclaredProperties skips user home when gradle user home is omitted`(
        @TempDir projectDir: File,
        @TempDir userHome: File,
    ) {
        File(userHome, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(projectDir, null)

        declared["userHome"]!! shouldBe emptyMap<String, String>()
    }

    @Test
    fun `readDeclaredProperties includes user home when requested`(
        @TempDir projectDir: File,
        @TempDir userHome: File,
    ) {
        File(userHome, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(projectDir, userHome)

        declared["userHome"]!!["org.gradle.caching"] shouldBe "true"
    }

    @Test
    fun `BuildCacheUrlRedactor masks credential-like cache property values`() {
        val sanitized = BuildCacheUrlRedactor.sanitizeCacheProperties(
            mapOf(
                "org.gradle.caching.http.credentials.password" to "secret-pass",
                "org.gradle.caching.remote.token" to "abc123",
                "org.gradle.caching" to "true",
            ),
        )

        sanitized["org.gradle.caching.http.credentials.password"] shouldBe "***"
        sanitized["org.gradle.caching.remote.token"] shouldBe "***"
        sanitized["org.gradle.caching"] shouldBe "true"
    }

    @Test
    fun `BuildCacheUrlRedactor strips userinfo from remote cache URLs`() {
        BuildCacheUrlRedactor.redactUserInfo("https://user:pass@cache.example.com/path") shouldBe
            "https://cache.example.com/path"
        BuildCacheUrlRedactor.redactUserInfo("https://cache.example.com") shouldBe
            "https://cache.example.com"
    }

    @Test
    fun `buildSummary redacts credentials from remoteBuildCacheUrl`() {
        val summary = BuildCacheStatusCollector.buildSummaryForTests(
            mapOf("org.gradle.caching.remote.url" to "https://user:pass@cache.example.com"),
            emptyMap(),
        )

        summary["remoteBuildCacheUrl"] shouldBe "https://cache.example.com"
        summary["remoteBuildCacheConfigured"] shouldBe true
    }

    @Test
    fun `GradlePropertiesStreamCapture retains early cache keys from large property output`() {
        val capture = GradlePropertiesStreamCapture(retainKey = BuildCachePropertyKeys::isCacheRelated)
        val out = PrintStream(capture.asOutputStream(), true, StandardCharsets.UTF_8)
        out.println("org.gradle.caching: true")
        repeat(50_000) { index ->
            out.println("filler.property.$index=value")
        }
        out.println("org.gradle.parallel=true")

        val properties = capture.snapshotProperties()

        properties["org.gradle.caching"] shouldBe "true"
        properties["org.gradle.parallel"] shouldBe "true"
        properties.size shouldBe 2
    }

    @Test
    fun `BuildCacheStatusOptions fromArgs uses defaults`() {
        val options = BuildCacheStatusOptions.fromArgs(emptyMap())

        options.includeLastMcpBuild.shouldBeTrue()
        options.includeLocalCacheDetails.shouldBeTrue()
        options.includeDeclaredProperties.shouldBeTrue()
        options.probeConfigurationCache.shouldBeFalse()
    }

    @Test
    fun `LastMcpBuildInsight toResponseMap encodes as JSON object`() {
        val insight = LastMcpBuildInsight(
            buildId = "build-1",
            kind = "tasks",
            tasks = listOf("build"),
            testClasses = emptyList(),
            finishedAt = "2026-01-01T00:00:00Z",
            outcome = "success",
            taskSummaryLine = "5 actionable tasks: 5 executed",
            resultLine = "BUILD SUCCESSFUL",
            taskStats = TaskExecutionStats(5, 5, null, null),
        )
        val payload = mapOf("lastMcpBuild" to insight.toResponseMap())
        val decoded = decodeMcpJsonMap(encodeMcpJsonDynamic(payload))

        @Suppress("UNCHECKED_CAST")
        val lastMcpBuild = decoded["lastMcpBuild"] as Map<String, Any?>
        lastMcpBuild["buildId"] shouldBe "build-1"
        @Suppress("UNCHECKED_CAST")
        val taskStats = lastMcpBuild["taskStats"] as Map<String, Any?>
        taskStats["executed"] shouldBe 5
    }
}
