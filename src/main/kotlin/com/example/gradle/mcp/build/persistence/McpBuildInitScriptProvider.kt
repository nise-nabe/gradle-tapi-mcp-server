package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.protocol.ClasspathResources
import java.util.concurrent.ConcurrentHashMap

object McpBuildInitScriptProvider {
    private const val MAIN_RESOURCE_PATH = "/mcp-build-recorder.init.gradle"
    private const val CONFIGURATION_CACHE_RESOURCE_PATH = "/mcp-build-recorder-configuration-cache.init.gradle"

    private val extractedPaths = ConcurrentHashMap<String, String>()

    fun initScriptPath(): String =
        extractCached(MAIN_RESOURCE_PATH, "mcp-build-recorder-", ".init.gradle")

    fun configurationCacheInitScriptPath(): String =
        extractCached(
            CONFIGURATION_CACHE_RESOURCE_PATH,
            "mcp-build-recorder-configuration-cache-",
            ".init.gradle",
        )

    private fun extractCached(resourcePath: String, prefix: String, suffix: String): String =
        extractedPaths.computeIfAbsent(resourcePath) {
            ClasspathResources
                .copyToTempFile(
                    ClasspathResources.require(McpBuildInitScriptProvider::class.java, resourcePath),
                    prefix,
                    suffix,
                ).absolutePath
        }

    internal fun resetCacheForTests() {
        extractedPaths.clear()
    }
}
