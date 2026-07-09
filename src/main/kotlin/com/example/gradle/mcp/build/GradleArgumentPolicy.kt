package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException

internal object GradleArgumentPolicy {
    private val MCP_CONTROL_PROPERTY_PREFIX = Regex("""(?i)^-P(?:mcp\.|project\.mcp\.)""")

    fun requireNoInitScript(arguments: List<String>) {
        var index = 0
        while (index < arguments.size) {
            when (val arg = arguments[index]) {
                "--init-script", "-I" -> throw initScriptException(arg)
                else -> {
                    if (arg.startsWith("--init-script=") || isCombinedInitScriptFlag(arg)) {
                        throw initScriptException(arg)
                    }
                    if (arg.startsWith("@")) {
                        throw argsFileException(arg)
                    }
                    index++
                }
            }
        }
    }

    fun requireNoMcpControlArguments(arguments: List<String>) {
        arguments.forEach { arg ->
            if (isMcpControlArgument(arg)) {
                throw mcpControlArgumentException(arg)
            }
        }
    }

    internal fun isMcpControlArgument(arg: String): Boolean =
        MCP_CONTROL_PROPERTY_PREFIX.containsMatchIn(arg)

    private fun isCombinedInitScriptFlag(arg: String): Boolean =
        arg.startsWith("-I") && arg.length > 2

    private fun initScriptException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle init scripts are not allowed in arguments ($flag). " +
                "The MCP server injects its own init script for build recording.",
        )

    private fun argsFileException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle argument files are not allowed in arguments ($flag). " +
                "They can inject init scripts that conflict with MCP build recording.",
        )

    private fun mcpControlArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "MCP-controlled project properties cannot be set in arguments ($flag). " +
                "The server injects persistence metadata and init scripts automatically.",
        )
}
