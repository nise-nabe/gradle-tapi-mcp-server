package com.example.gradle.mcp.build.persistence

import java.io.File

object McpBuildInitScriptProvider {
    private const val RESOURCE_PATH = "/mcp-build-recorder.init.gradle"

    private var cachedPath: String? = null

    fun initScriptPath(): String {
        cachedPath?.let { return it }
        val resource = McpBuildInitScriptProvider::class.java.getResource(RESOURCE_PATH)
            ?: error("$RESOURCE_PATH not found on classpath")
        val temp = File.createTempFile("mcp-build-recorder-", ".init.gradle")
        temp.deleteOnExit()
        resource.openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        cachedPath = temp.absolutePath
        return temp.absolutePath
    }

    internal fun resetCacheForTests() {
        cachedPath = null
    }
}
