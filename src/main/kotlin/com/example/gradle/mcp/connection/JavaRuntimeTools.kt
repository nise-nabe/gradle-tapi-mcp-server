package com.example.gradle.mcp.connection

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.ProjectConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

private const val JAVA_RUNTIMES_DETECTION_SOURCE_BUILD_ENVIRONMENT = "buildEnvironment"
private const val JAVA_RUNTIMES_DETECTION_SOURCE_TOOLCHAINS_TASK = "javaToolchainsTask"

private val javaToolchainsSectionRegex = Regex("""^\+\s+(.+?)\s*$""")
private val javaToolchainsPropertyRegex = Regex("""^\|\s*([^:]+):\s*(.*)$""")

internal fun javaRuntimesSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
            "includeToolchains" to booleanProperty(
                "List local JDKs via javaToolchains. Default true; false returns daemon Java only.",
            ),
        ),
    )

internal data class DaemonJavaRuntime(
    val javaHome: String?,
    val javaVersion: String?,
)

internal data class DetectedJdk(
    val javaHome: String,
    val javaVersion: String,
)

internal data class JavaRuntimesSnapshot(
    val daemon: DaemonJavaRuntime,
    val detectedJdks: List<DetectedJdk>,
    val detectionSource: String,
)

internal fun JavaRuntimesSnapshot.toMap(projectDirectory: String): Map<String, Any?> =
    linkedMapOf(
        "projectDirectory" to projectDirectory,
        "detectionSource" to detectionSource,
        "daemon" to mapOf(
            "javaHome" to daemon.javaHome,
            "javaVersion" to daemon.javaVersion,
        ),
        "detectedJdks" to detectedJdks.map { detected ->
            mapOf(
                "javaHome" to detected.javaHome,
                "javaVersion" to detected.javaVersion,
            )
        },
    )

internal object JavaToolchainsParser {
    fun parse(output: String): List<DetectedJdk> {
        if (output.isBlank()) {
            return emptyList()
        }

        val sections = mutableListOf<JavaToolchainsSection>()
        var title: String? = null
        var properties = linkedMapOf<String, String>()

        fun flush() {
            val sectionTitle = title ?: return
            sections += JavaToolchainsSection(sectionTitle, properties.toMap())
        }

        normalizeOutput(output).lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val headerMatch = javaToolchainsSectionRegex.matchEntire(line.trimStart())
            if (headerMatch != null) {
                flush()
                title = headerMatch.groupValues[1].trim()
                properties = linkedMapOf()
                return@forEach
            }

            val propertyMatch = javaToolchainsPropertyRegex.matchEntire(line.trimStart())
            if (propertyMatch != null && title != null) {
                properties[propertyMatch.groupValues[1].trim()] = propertyMatch.groupValues[2].trim()
            }
        }
        flush()

        return sections.mapNotNull(JavaToolchainsSection::toDetectedJdk)
            .distinctBy(DetectedJdk::javaHome)
    }

    private fun normalizeOutput(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}

private data class JavaToolchainsSection(
    val title: String,
    val properties: Map<String, String>,
) {
    fun toDetectedJdk(): DetectedJdk? {
        if (title.equals("Options", ignoreCase = true)) {
            return null
        }

        val isJdk = properties["Is JDK"]?.equals("true", ignoreCase = true)
            ?: title.contains("JDK", ignoreCase = true)
        if (!isJdk) {
            return null
        }

        val javaHome = properties["Location"]?.takeIf(String::isNotBlank) ?: return null
        val javaVersion = versionFromTitle(title)
            ?: properties["Language Version"]?.takeIf(String::isNotBlank)
            ?: return null
        return DetectedJdk(javaHome = javaHome, javaVersion = javaVersion)
    }

    private fun versionFromTitle(title: String): String? =
        title.substringAfter('(', "").substringBeforeLast(")", "").trim().takeIf(String::isNotBlank)
}

internal object JavaRuntimesCollector {
    fun collect(
        projectDirectory: File,
        connection: ProjectConnection,
        connectionManager: GradleConnectionManager,
        cachedEnvironment: BuildEnvironmentSnapshot?,
        includeToolchains: Boolean = true,
    ): JavaRuntimesSnapshot {
        val environment = cachedEnvironment
            ?: connectionManager.fetchAndCacheEnvironment(projectDirectory, connection)
        val detectedJdks = if (includeToolchains) {
            detectInstalledJdks(connection, projectDirectory)
        } else {
            emptyList()
        }
        return JavaRuntimesSnapshot(
            daemon = DaemonJavaRuntime(
                javaHome = environment.javaHome,
                javaVersion = environment.javaVersion,
            ),
            detectedJdks = detectedJdks,
            detectionSource = if (includeToolchains) {
                JAVA_RUNTIMES_DETECTION_SOURCE_TOOLCHAINS_TASK
            } else {
                JAVA_RUNTIMES_DETECTION_SOURCE_BUILD_ENVIRONMENT
            },
        )
    }

    private fun detectInstalledJdks(
        connection: ProjectConnection,
        projectDirectory: File,
    ): List<DetectedJdk> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            val launcher = connection.newBuild()
                .forTasks("javaToolchains")
                .addArguments("-q")
            launcher.setStandardOutput(PrintStream(stdout, true, StandardCharsets.UTF_8))
            launcher.setStandardError(PrintStream(stderr, true, StandardCharsets.UTF_8))
            launcher.run()
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw McpException(
                McpErrorCode.BUILD_FAILED,
                buildFailureMessage(projectDirectory, exception, stdout, stderr),
                exception,
            )
        }
        return JavaToolchainsParser.parse(stdout.toString(StandardCharsets.UTF_8))
    }

    private fun buildFailureMessage(
        projectDirectory: File,
        exception: Exception,
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream,
    ): String {
        val stdoutExcerpt = stdout.toString(StandardCharsets.UTF_8).trim().take(300)
        val stderrExcerpt = stderr.toString(StandardCharsets.UTF_8).trim().take(300)
        return buildString {
            append("Failed to detect installed JDKs for ${projectDirectory.path} via `javaToolchains -q`.")
            exception.message?.takeIf(String::isNotBlank)?.let { append(" $it") }
            if (stderrExcerpt.isNotBlank()) {
                append(" stderrExcerpt=")
                append(stderrExcerpt)
            } else if (stdoutExcerpt.isNotBlank()) {
                append(" stdoutExcerpt=")
                append(stdoutExcerpt)
            }
        }
    }
}

private fun toolchainDetectionBlockedMessage(projectDirectory: File): String =
    "Cannot detect installed JDKs while a Gradle build is running for ${projectDirectory.path}. " +
        "Wait for the build to finish, call gradle_get_build_status, or set includeToolchains=false."

internal fun requireNoActiveBuildForToolchainDetection(
    includeToolchains: Boolean,
    projectDirectory: File,
    buildExecutionManager: BuildExecutionManager,
) {
    if (!includeToolchains) {
        return
    }
    ProjectLifecycleGuard.withNoActiveBuild(
        projectDirectory = projectDirectory,
        buildExecutionManager = buildExecutionManager,
        message = ::toolchainDetectionBlockedMessage,
    ) { }
}

context(runtime: GradleMcpRuntime)
fun Server.registerJavaRuntimeTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_java_runtimes",
        description = McpToolDescriptions.JAVA_RUNTIMES,
        schema = javaRuntimesSchema(),
    ) { args ->
        val includeToolchains = args.optionalBoolean("includeToolchains", default = true)
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
        if (!includeToolchains) {
            return@registerTool runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val runtimes = JavaRuntimesCollector.collect(
                    projectDirectory = projectDirectory,
                    connection = connection,
                    connectionManager = runtime.connectionManager,
                    cachedEnvironment = runtime.connectionManager.cachedEnvironment(projectDirectory),
                    includeToolchains = false,
                )
                jsonResult(runtimes.toMap(projectDirectory.path))
            }
        }
        return@registerTool ProjectLifecycleGuard.withNoActiveBuild(
            projectDirectory = projectDirectory,
            buildExecutionManager = runtime.buildExecutionManager,
            message = ::toolchainDetectionBlockedMessage,
        ) {
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val runtimes = JavaRuntimesCollector.collect(
                    projectDirectory = projectDirectory,
                    connection = connection,
                    connectionManager = runtime.connectionManager,
                    cachedEnvironment = runtime.connectionManager.cachedEnvironment(projectDirectory),
                    includeToolchains = true,
                )
                jsonResult(runtimes.toMap(projectDirectory.path))
            }
        }
    }
}
