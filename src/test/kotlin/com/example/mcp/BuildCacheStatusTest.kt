package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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

        assertEquals("true", properties["org.gradle.caching"])
        assertEquals("https://cache.example.com", properties["org.gradle.caching.remote.url"])
        assertEquals("false", properties["org.gradle.parallel"])
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

        assertEquals(2, filtered.size)
        assertFalse(filtered.containsKey("version"))
    }

    @Test
    fun `TaskExecutionStatsParser extracts executed and from cache counts`() {
        val stats = TaskExecutionStatsParser.parse("12 actionable tasks: 10 executed, 2 from cache")

        requireNotNull(stats)
        assertEquals(12, stats.actionableTasks)
        assertEquals(10, stats.executed)
        assertEquals(2, stats.fromCache)
        assertNull(stats.upToDate)
    }

    @Test
    fun `TaskExecutionStatsParser extracts up-to-date counts`() {
        val stats = TaskExecutionStatsParser.parse("5 actionable tasks: 5 up-to-date")

        requireNotNull(stats)
        assertEquals(5, stats.actionableTasks)
        assertEquals(5, stats.upToDate)
        assertNull(stats.executed)
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
        assertEquals(1, buildCacheDirectories.size)
        assertEquals(true, buildCacheDirectories.single()["exists"])
        assertEquals(1, buildCacheDirectories.single()["fileCount"])
        assertNull(buildCacheDirectories.single()["fileCountCapped"])
    }

    @Test
    fun `directorySummary exposes fileCountCapped when walk limit is reached`(@TempDir tempDir: File) {
        repeat(3) { index ->
            File(tempDir, "file$index.txt").writeText("x")
        }

        val summary = LocalGradleCacheInspector.summarizeDirectoryForTests(tempDir, maxFiles = 2)

        assertEquals(2, summary["fileCount"])
        assertEquals(true, summary["fileCountCapped"])
    }

    @Test
    fun `directorySummary does not cap when file count equals limit exactly`(@TempDir tempDir: File) {
        repeat(2) { index ->
            File(tempDir, "file$index.txt").writeText("x")
        }

        val summary = LocalGradleCacheInspector.summarizeDirectoryForTests(tempDir, maxFiles = 2)

        assertEquals(2, summary["fileCount"])
        assertNull(summary["fileCountCapped"])
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
        assertEquals(listOf("8.0", "8.14", "9.0", "9.1", "10.0"), stores.map { it["gradleVersionDir"] })
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
        assertEquals(listOf("8.14", "9.0", "10.0"), stores.map { it["gradleVersionDir"] })
    }

    @Test
    fun `LocalGradleCacheInspector includes empty cache lists when gradle user home is missing`(@TempDir tempDir: File) {
        val inspected = LocalGradleCacheInspector.inspect(
            gradleUserHome = null,
            projectDirectory = tempDir,
            includeDetails = false,
        )

        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<Map<String, Any?>>(), inspected["buildCacheDirectories"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<Map<String, Any?>>(), inspected["configurationCacheStores"])
        assertNull(inspected["gradleUserHome"])
    }

    @Test
    fun `summary declaredInProjectFiles reflects project gradle properties`(@TempDir tempDir: File) {
        File(tempDir, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(tempDir, null)
        val summary = BuildCacheStatusCollector.buildSummaryForTests(emptyMap(), declared)

        assertEquals(true, summary["declaredInProjectFiles"])
    }

    @Test
    fun `readDeclaredProperties skips user home when gradle user home is omitted`(
        @TempDir projectDir: File,
        @TempDir userHome: File,
    ) {
        File(userHome, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(projectDir, null)

        assertTrue(declared["userHome"]!!.isEmpty())
    }

    @Test
    fun `readDeclaredProperties includes user home when requested`(
        @TempDir projectDir: File,
        @TempDir userHome: File,
    ) {
        File(userHome, "gradle.properties").writeText("org.gradle.caching=true\n")

        val declared = BuildCacheStatusCollector.readDeclaredPropertiesForTests(projectDir, userHome)

        assertEquals("true", declared["userHome"]!!["org.gradle.caching"])
    }

    @Test
    fun `BuildCacheUrlRedactor strips userinfo from remote cache URLs`() {
        assertEquals(
            "https://cache.example.com/path",
            BuildCacheUrlRedactor.redactUserInfo("https://user:pass@cache.example.com/path"),
        )
        assertEquals(
            "https://cache.example.com",
            BuildCacheUrlRedactor.redactUserInfo("https://cache.example.com"),
        )
    }

    @Test
    fun `buildSummary redacts credentials from remoteBuildCacheUrl`() {
        val summary = BuildCacheStatusCollector.buildSummaryForTests(
            mapOf("org.gradle.caching.remote.url" to "https://user:pass@cache.example.com"),
            emptyMap(),
        )

        assertEquals("https://cache.example.com", summary["remoteBuildCacheUrl"])
        assertEquals(true, summary["remoteBuildCacheConfigured"])
    }

    @Test
    fun `BuildCacheStatusOptions fromArgs uses defaults`() {
        val options = BuildCacheStatusOptions.fromArgs(emptyMap())

        assertTrue(options.includeLastMcpBuild)
        assertTrue(options.includeLocalCacheDetails)
        assertTrue(options.includeDeclaredProperties)
        assertFalse(options.probeConfigurationCache)
    }
}
