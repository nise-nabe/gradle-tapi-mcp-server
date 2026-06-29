package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.GradleEnvironment
import org.gradle.tooling.model.build.JavaEnvironment
import org.junit.jupiter.api.Test
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class JavaRuntimeToolsTest {
    @Test
    fun `parse keeps JDK entries and prefers the full version from the title`() {
        val detected = JavaToolchainsParser.parse(
            """
             + Options
                 | Auto-detection:     Enabled
                 | Auto-download:      Enabled

             + Ubuntu JDK 17 (17.0.19+10-1-24.04.2-Ubuntu)
                 | Location:           /usr/lib/jvm/java-17-openjdk-amd64
                 | Language Version:   17
                 | Vendor:             Ubuntu
                 | Is JDK:             true

             + Foo JRE 11
                 | Location:           /usr/lib/jvm/java-11-openjdk-amd64
                 | Language Version:   11
                 | Is JDK:             false

             + Eclipse Temurin JDK 21
                 | Location:           /opt/jdks/temurin-21
                 | Language Version:   21
                 | Vendor:             Eclipse Temurin
                 | Is JDK:             true
            """.trimIndent(),
        )

        detected shouldContainExactly listOf(
            DetectedJdk(
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19+10-1-24.04.2-Ubuntu",
            ),
            DetectedJdk(
                javaHome = "/opt/jdks/temurin-21",
                javaVersion = "21",
            ),
        )
    }

    @Test
    fun `collect reuses cached daemon environment and serializes token efficient response`() {
        val getModelCalls = AtomicInteger(0)
        val launcher = recordingBuildLauncher(
            stdoutText = """
                 + Ubuntu JDK 17 (17.0.19+10-1-24.04.2-Ubuntu)
                     | Location:           /usr/lib/jvm/java-17-openjdk-amd64
                     | Language Version:   17
                     | Is JDK:             true
            """.trimIndent(),
        )
        val connection = projectConnectionProxy(
            getModelCalls = getModelCalls,
            buildEnvironment = null,
            launcher = launcher.launcher,
        )

        val snapshot = JavaRuntimesCollector.collect(
            projectDirectory = File("/workspace"),
            connection = connection,
            cachedEnvironment = BuildEnvironmentSnapshot(
                gradleVersion = "9.6.0",
                gradleUserHome = "/gradle/home",
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19",
                jvmArguments = emptyList(),
            ),
        )

        snapshot.toMap("/workspace") shouldBe linkedMapOf(
            "projectDirectory" to "/workspace",
            "detectionSource" to "javaToolchainsTask",
            "daemon" to mapOf(
                "javaHome" to "/usr/lib/jvm/java-17-openjdk-amd64",
                "javaVersion" to "17.0.19",
            ),
            "detectedJdks" to listOf(
                mapOf(
                    "javaHome" to "/usr/lib/jvm/java-17-openjdk-amd64",
                    "javaVersion" to "17.0.19+10-1-24.04.2-Ubuntu",
                ),
            ),
        )
        launcher.tasks shouldContainExactly listOf("javaToolchains")
        launcher.arguments shouldContainExactly listOf("-q")
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `collect loads BuildEnvironment when the cache is missing`() {
        val getModelCalls = AtomicInteger(0)
        val launcher = recordingBuildLauncher(
            stdoutText = """
                 + Ubuntu JDK 21 (21.0.10+7-Ubuntu-124.04)
                     | Location:           /usr/lib/jvm/java-21-openjdk-amd64
                     | Language Version:   21
                     | Is JDK:             true
            """.trimIndent(),
        )
        val connection = projectConnectionProxy(
            getModelCalls = getModelCalls,
            buildEnvironment = buildEnvironmentProxy(
                javaHome = "/usr/lib/jvm/java-21-openjdk-amd64",
            ),
            launcher = launcher.launcher,
        )

        val snapshot = JavaRuntimesCollector.collect(
            projectDirectory = File("/workspace"),
            connection = connection,
            cachedEnvironment = null,
        )

        snapshot.daemon shouldBe DaemonJavaRuntime(
            javaHome = "/usr/lib/jvm/java-21-openjdk-amd64",
            javaVersion = "21.0.10",
        )
        snapshot.detectedJdks shouldContainExactly listOf(
            DetectedJdk(
                javaHome = "/usr/lib/jvm/java-21-openjdk-amd64",
                javaVersion = "21.0.10+7-Ubuntu-124.04",
            ),
        )
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `collect wraps javaToolchains failures as BUILD_FAILED`() {
        val connection = projectConnectionProxy(
            getModelCalls = AtomicInteger(0),
            buildEnvironment = null,
            launcher = recordingBuildLauncher(
                runException = RuntimeException("Task 'javaToolchains' not found in root project."),
            ).launcher,
        )

        val error = shouldThrow<McpException> {
            JavaRuntimesCollector.collect(
                projectDirectory = File("/workspace"),
                connection = connection,
                cachedEnvironment = BuildEnvironmentSnapshot(
                    gradleVersion = "9.6.0",
                    gradleUserHome = null,
                    javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                    javaVersion = "17.0.19",
                    jvmArguments = emptyList(),
                ),
            )
        }

        error.code shouldBe McpErrorCode.BUILD_FAILED
        error.message shouldContain "javaToolchains -q"
        error.message shouldContain "Task 'javaToolchains' not found"
    }

    private fun projectConnectionProxy(
        getModelCalls: AtomicInteger,
        buildEnvironment: BuildEnvironment?,
        launcher: BuildLauncher,
    ): ProjectConnection =
        Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, method: Method, args ->
                when (method.name) {
                    "getModel" -> {
                        getModelCalls.incrementAndGet()
                        val modelType = args?.get(0) as Class<*>
                        when (modelType) {
                            BuildEnvironment::class.java -> buildEnvironment
                            else -> null
                        }
                    }
                    "newBuild" -> launcher
                    else -> defaultProxyValue(method)
                }
            },
        ) as ProjectConnection

    private data class RecordingBuildLauncher(
        val launcher: BuildLauncher,
        val tasks: MutableList<String>,
        val arguments: MutableList<String>,
    )

    private fun recordingBuildLauncher(
        stdoutText: String = "",
        stderrText: String = "",
        runException: Exception? = null,
    ): RecordingBuildLauncher {
        val tasks = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        val stdout = arrayOfNulls<PrintStream>(1)
        val stderr = arrayOfNulls<PrintStream>(1)
        val self = arrayOfNulls<Any>(1)
        self[0] = Proxy.newProxyInstance(
            BuildLauncher::class.java.classLoader,
            arrayOf(BuildLauncher::class.java),
            InvocationHandler { _, method: Method, args ->
                when (method.name) {
                    "forTasks" -> {
                        tasks += normalizeStrings(args)
                        self[0]
                    }
                    "addArguments" -> {
                        arguments += normalizeStrings(args)
                        self[0]
                    }
                    "setStandardOutput" -> {
                        stdout[0] = args?.get(0) as PrintStream
                        self[0]
                    }
                    "setStandardError" -> {
                        stderr[0] = args?.get(0) as PrintStream
                        self[0]
                    }
                    "run" -> {
                        stdout[0]?.print(stdoutText)
                        stdout[0]?.flush()
                        stderr[0]?.print(stderrText)
                        stderr[0]?.flush()
                        runException?.let { throw it }
                        null
                    }
                    else -> self[0]
                }
            },
        )
        return RecordingBuildLauncher(self[0] as BuildLauncher, tasks, arguments)
    }

    private fun buildEnvironmentProxy(
        javaHome: String,
    ): BuildEnvironment {
        val gradleEnvironment = Proxy.newProxyInstance(
            GradleEnvironment::class.java.classLoader,
            arrayOf(GradleEnvironment::class.java),
            InvocationHandler { _, method: Method, _ ->
                when (method.name) {
                    "getGradleVersion" -> "9.6.0"
                    "getGradleUserHome" -> File("/gradle/home")
                    else -> defaultProxyValue(method)
                }
            },
        ) as GradleEnvironment

        val javaEnvironment = Proxy.newProxyInstance(
            JavaEnvironment::class.java.classLoader,
            arrayOf(JavaEnvironment::class.java),
            InvocationHandler { _, method: Method, _ ->
                when (method.name) {
                    "getJavaHome" -> File(javaHome)
                    "getJvmArguments" -> emptyList<String>()
                    else -> defaultProxyValue(method)
                }
            },
        ) as JavaEnvironment

        return Proxy.newProxyInstance(
            BuildEnvironment::class.java.classLoader,
            arrayOf(BuildEnvironment::class.java),
            InvocationHandler { _, method: Method, _ ->
                when (method.name) {
                    "getGradle" -> gradleEnvironment
                    "getJava" -> javaEnvironment
                    "getVersionInfo" -> "Gradle 9.6.0"
                    else -> defaultProxyValue(method)
                }
            },
        ) as BuildEnvironment
    }

    private fun normalizeStrings(args: Array<out Any?>?): List<String> =
        args?.flatMap { value ->
            when (value) {
                is Array<*> -> value.filterIsInstance<String>()
                is Iterable<*> -> value.filterIsInstance<String>()
                is String -> listOf(value)
                else -> emptyList()
            }
        }.orEmpty()

    private fun defaultProxyValue(method: Method): Any? =
        when (method.returnType) {
            java.lang.Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            List::class.java -> emptyList<Any>()
            String::class.java -> ""
            else -> null
        }
}
