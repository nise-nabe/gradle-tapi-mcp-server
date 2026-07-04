package com.example.gradle.mcp

/**
 * JVM entry point that configures kotlin-logging before the Kotlin MCP SDK (and its
 * transitive kotlin-logging dependency) initializes, keeping MCP stdio stdout JSON-only.
 */
object GradleTapiMcpServerLauncher {
    init {
        System.setProperty("kotlin-logging-to-slf4j", "true")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        disableKotlinLoggingStartupMessage()
        runGradleTapiMcpServer()
    }

    private fun disableKotlinLoggingStartupMessage() {
        runCatching {
            val configurationClass = Class.forName("io.github.oshai.kotlinlogging.KotlinLoggingConfiguration")
            val field = configurationClass.getDeclaredField("logStartupMessage")
            if (field.trySetAccessible()) {
                field.setBoolean(null, false)
            }
        }
    }
}
