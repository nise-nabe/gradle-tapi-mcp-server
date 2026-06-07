package com.example.gradle.mcp.cache

data class BuildCacheStatusOptions(
    val includeLastMcpBuild: Boolean = true,
    val includeLocalCacheDetails: Boolean = true,
    val includeDeclaredProperties: Boolean = true,
    val probeConfigurationCache: Boolean = false,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): BuildCacheStatusOptions =
            BuildCacheStatusOptions(
                includeLastMcpBuild = readBooleanArg(args, "includeLastMcpBuild", default = true),
                includeLocalCacheDetails = readBooleanArg(args, "includeLocalCacheDetails", default = true),
                includeDeclaredProperties = readBooleanArg(args, "includeDeclaredProperties", default = true),
                probeConfigurationCache = readBooleanArg(args, "probeConfigurationCache", default = false),
            )

        private fun readBooleanArg(args: Map<String, Any>, key: String, default: Boolean): Boolean =
            when (val value = args[key]) {
                is Boolean -> value
                else -> default
            }
    }
}
