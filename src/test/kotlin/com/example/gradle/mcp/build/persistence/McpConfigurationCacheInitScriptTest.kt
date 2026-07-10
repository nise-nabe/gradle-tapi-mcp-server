package com.example.gradle.mcp.build.persistence

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class McpConfigurationCacheInitScriptTest {
    @BeforeEach
    fun resetProviderCache() {
        McpBuildInitScriptProvider.resetCacheForTests()
    }

    @Test
    fun `configuration cache init script delegates appendEvent to McpBuildRecorderSupport`() {
        val script = readConfigurationCacheInitScript()

        script shouldContain "McpBuildRecorderSupport.appendEvent(launcherContext().recordDirPath, event)"
        script.lines()
            .filter { it.contains("appendEvent(launcherContext().recordDirPath, event)") }
            .filterNot { it.contains("McpBuildRecorderSupport.") }
            .shouldBeEmpty()
    }

    private fun readConfigurationCacheInitScript(): String =
        File(McpBuildInitScriptProvider.configurationCacheInitScriptPath()).readText()
}
