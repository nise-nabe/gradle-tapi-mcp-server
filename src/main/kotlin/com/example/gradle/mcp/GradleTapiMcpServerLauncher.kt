package com.example.gradle.mcp

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
            System.err.println(e.message)
            System.err.println(ServerCliOptions.USAGE)
            exitProcess(2)
        }
        if (options.showHelp) {
            println(ServerCliOptions.USAGE)
            return
        }
        runGradleTapiMcpServer(options)
    }
}
