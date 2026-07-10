package com.example.gradle.mcp.build.persistence

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class McpBuildInitScriptProviderTest {
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
