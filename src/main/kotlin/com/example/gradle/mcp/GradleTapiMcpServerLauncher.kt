package com.example.gradle.mcp

import com.example.gradle.mcp.protocol.ToolSelectionException
import com.example.gradle.mcp.server.ServerCliOptions
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess

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
        val options = try {
            ServerCliOptions.parse(args)
        } catch (e: IllegalArgumentException) {
            exitWithCliError(e)
        }
        if (options.showHelp) {
            println(ServerCliOptions.USAGE)
            return
        }
        try {
            runGradleTapiMcpServer(options)
        } catch (e: ToolSelectionException) {
            exitWithCliError(e)
        }
    }

    private fun exitWithCliError(error: IllegalArgumentException): Nothing {
        System.err.println(error.message)
        System.err.println(ServerCliOptions.USAGE)
        exitProcess(2)
    }
}
