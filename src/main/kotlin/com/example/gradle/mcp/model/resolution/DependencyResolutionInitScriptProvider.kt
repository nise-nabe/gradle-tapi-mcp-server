package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.protocol.ClasspathResources
import java.io.File

/**
 * Extracts the thin `:resolution-model` jar and init script used to register
 * [com.example.gradle.mcp.resolution.McpDependencyResolutionPlugin] in target builds.
 */
object DependencyResolutionInitScriptProvider {
    private const val INIT_RESOURCE = "/mcp-dependency-resolution.init.gradle"
    private const val JAR_RESOURCE = "/META-INF/mcp/resolution-model.jar"
    private const val JAR_PLACEHOLDER = "@@RESOLUTION_MODEL_JAR@@"

    @Volatile
    private var cachedInitScript: File? = null

    @Volatile
    private var cachedJar: File? = null

    fun initScriptPath(): String = ensureMaterials().initScript.absolutePath

    fun resolutionModelJarPath(): String = ensureMaterials().jar.absolutePath

    @Synchronized
    private fun ensureMaterials(): Materials {
        cachedInitScript?.let { script ->
            cachedJar?.let { jar ->
                if (script.isFile && jar.isFile) {
                    return Materials(script, jar)
                }
            }
        }
        val jar = extractJar()
        val script = writeInitScript(jar)
        cachedJar = jar
        cachedInitScript = script
        return Materials(script, jar)
    }

    private fun extractJar(): File =
        ClasspathResources.copyToTempFile(
            ClasspathResources.require(
                DependencyResolutionInitScriptProvider::class.java,
                JAR_RESOURCE,
                hint = "Build the server jar so :resolution-model is embedded under META-INF/mcp/.",
            ),
            "mcp-resolution-model-",
            ".jar",
        )

    private fun writeInitScript(jar: File): File {
        val template = ClasspathResources.require(
            DependencyResolutionInitScriptProvider::class.java,
            INIT_RESOURCE,
        )
        val jarLiteral = jar.absolutePath.replace("\\", "/").replace("'", "\\'")
        val content = template.readText().replace(JAR_PLACEHOLDER, jarLiteral)
        val temp = File.createTempFile("mcp-dependency-resolution-", ".init.gradle")
        temp.deleteOnExit()
        temp.writeText(content)
        return temp
    }

    internal fun resetCacheForTests() {
        cachedInitScript = null
        cachedJar = null
    }

    private data class Materials(val initScript: File, val jar: File)
}
