package com.example.gradle.mcp.build.persistence

import java.io.File

object McpBuildInitScriptProvider {
    private const val MAIN_RESOURCE_PATH = "/mcp-build-recorder.init.gradle"
    private const val CONFIGURATION_CACHE_RESOURCE_PATH = "/mcp-build-recorder-configuration-cache.init.gradle"

    private var cachedMainPath: String? = null
    private var cachedConfigurationCachePath: String? = null

    fun initScriptPath(): String {
        cachedMainPath?.let { return it }
        cachedMainPath = extractResourceToTempFile(MAIN_RESOURCE_PATH, "mcp-build-recorder-", ".init.gradle")
        return cachedMainPath!!
    }

    fun configurationCacheInitScriptPath(): String {
        cachedConfigurationCachePath?.let { return it }
        cachedConfigurationCachePath = extractResourceToTempFile(
            CONFIGURATION_CACHE_RESOURCE_PATH,
            "mcp-build-recorder-configuration-cache-",
            ".init.gradle",
        )
        return cachedConfigurationCachePath!!
    }

    private fun extractResourceToTempFile(resourcePath: String, prefix: String, suffix: String): String {
        val resource = McpBuildInitScriptProvider::class.java.getResource(resourcePath)
            ?: error("$resourcePath not found on classpath")
        val temp = File.createTempFile(prefix, suffix)
        temp.deleteOnExit()
        resource.openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp.absolutePath
    }

    internal fun resetCacheForTests() {
        cachedMainPath = null
        cachedConfigurationCachePath = null
    }
}
