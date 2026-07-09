package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException

internal object GradleArgumentPolicy {
    private const val GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX = "org.gradle.project."

    fun validateUserBuildArguments(arguments: List<String>, jvmArguments: List<String>) {
        requireNoInitScript(arguments)
        requireNoMcpControlArguments(arguments)
        requireNoMcpControlJvmArguments(jvmArguments)
    }

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
        var index = 0
        while (index < arguments.size) {
            when (val arg = arguments[index]) {
                "-P" -> {
                    val propertyToken = arguments.getOrNull(index + 1)
                    if (propertyToken != null && isMcpControlProjectPropToken(propertyToken)) {
                        throw mcpControlArgumentException("-P $propertyToken")
                    }
                    index += if (propertyToken != null && !propertyToken.startsWith("-")) 2 else 1
                }
                "--project-prop" -> {
                    val propertyToken = arguments.getOrNull(index + 1)
                    if (propertyToken != null && isMcpControlProjectPropToken(propertyToken)) {
                        throw mcpControlArgumentException("--project-prop $propertyToken")
                    }
                    index += if (propertyToken != null && !propertyToken.startsWith("-")) 2 else 1
                }
                else -> {
                    when {
                        isMcpControlJvmArgument(arg) -> throw mcpControlArgumentException(arg)
                        isCombinedProjectPropertyFlag(arg) -> {
                            val propertyToken = arg.substring(2)
                            if (isMcpControlProjectPropToken(propertyToken)) {
                                throw mcpControlArgumentException(arg)
                            }
                            index++
                        }
                        arg.startsWith("--project-prop=", ignoreCase = true) -> {
                            val propertyToken = arg.substringAfter('=')
                            if (isMcpControlProjectPropToken(propertyToken)) {
                                throw mcpControlArgumentException(arg)
                            }
                            index++
                        }
                        else -> index++
                    }
                }
            }
        }
    }

    fun requireNoMcpControlJvmArguments(jvmArguments: List<String>) {
        jvmArguments.forEach { arg ->
            if (isMcpControlJvmArgument(arg)) {
                throw mcpControlJvmArgumentException(arg)
            }
        }
    }

    internal fun isMcpControlProjectPropToken(token: String): Boolean =
        isMcpControlPropertyName(propertyNameFromProjectPropToken(token))

    internal fun isMcpControlJvmArgument(arg: String): Boolean {
        if (!arg.startsWith("-D")) {
            return false
        }
        val propertyName = arg.removePrefix("-D").substringBefore('=')
        if (!propertyName.startsWith(GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX, ignoreCase = true)) {
            return false
        }
        val projectPropertyName = propertyName.removePrefix(GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX)
        return isMcpControlPropertyName(projectPropertyName)
    }

    internal fun isMcpControlPropertyName(propertyName: String): Boolean {
        val normalized = propertyName.trim()
        return normalized.startsWith("mcp.", ignoreCase = true) ||
            normalized.startsWith("project.mcp.", ignoreCase = true)
    }

    private fun propertyNameFromProjectPropToken(token: String): String =
        token.trim().substringBefore('=').trim()

    private fun isCombinedProjectPropertyFlag(arg: String): Boolean =
        arg.length > 2 && arg.startsWith("-P", ignoreCase = true)

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

    private fun mcpControlJvmArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "MCP-controlled project properties cannot be set in jvmArguments ($flag). " +
                "The server injects persistence metadata and init scripts automatically.",
        )
}
