package com.example.gradle.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration

/**
 * JVM entry point that configures kotlin-logging before the Kotlin MCP SDK (and its
 * transitive kotlin-logging dependency) initializes, keeping MCP stdio stdout JSON-only.
 */
object GradleTapiMcpServerLauncher {
    init {
        System.setProperty("kotlin-logging-to-slf4j", "true")
        KotlinLoggingConfiguration.logStartupMessage = false
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runGradleTapiMcpServer()
    }
}
