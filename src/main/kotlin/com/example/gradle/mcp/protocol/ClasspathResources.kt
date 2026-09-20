package com.example.gradle.mcp.protocol

import java.io.File
import java.net.URL

/**
 * Locates embedded classpath resources and materializes them as temp files
 * for Tooling API launchers that need real paths (e.g. `--init-script`).
 */
internal object ClasspathResources {

    fun require(anchor: Class<*>, resourcePath: String, hint: String? = null): URL =
        anchor.getResource(resourcePath)
            ?: error("$resourcePath not found on classpath" + (hint?.let { ". $it" } ?: ""))

    /** Copies [resource] into a [File.createTempFile] registered for [File.deleteOnExit]. */
    fun copyToTempFile(resource: URL, prefix: String, suffix: String): File {
        val temp = File.createTempFile(prefix, suffix)
        temp.deleteOnExit()
        resource.openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
    }
}
