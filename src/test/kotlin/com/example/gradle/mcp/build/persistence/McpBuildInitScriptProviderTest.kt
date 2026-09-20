package com.example.gradle.mcp.build.persistence

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class McpBuildInitScriptProviderTest {
    @Test
    fun `non-configuration-cache init script derives record dir boundary from metadata path`() {
        val script = readResource("/mcp-build-recorder.init.gradle")

        script shouldContain "projectDirectoryFromMetadataPath(gradle.ext.mcpLauncherMetadataPath)"
        script shouldNotContain "gradle.rootProject.projectDir"
    }

    @Test
    fun `configuration cache init script delegates appendEvent to McpBuildRecorderSupport`() {
        val script = readResource("/mcp-build-recorder-configuration-cache.init.gradle")

        script shouldContain "McpBuildRecorderSupport.appendEvent(launcherContext().recordDirPath, event)"
        script.lines()
            .filter { it.contains("appendEvent(launcherContext().recordDirPath, event)") }
            .single() shouldContain "McpBuildRecorderSupport."
    }

    private fun readResource(path: String): String =
        McpBuildInitScriptProviderTest::class.java.getResource(path)?.readText()
            ?: error("$path not found on classpath")
}
