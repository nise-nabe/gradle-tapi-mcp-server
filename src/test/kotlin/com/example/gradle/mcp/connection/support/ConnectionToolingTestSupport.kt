package com.example.gradle.mcp.connection.support

import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.GradleEnvironment
import org.gradle.tooling.model.build.JavaEnvironment
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

internal data class BuildEnvironmentProxyOptions(
    val javaHome: String = "/jdk/home",
    val gradleVersion: String = "9.6",
    val gradleUserHome: File? = File("/gradle/home"),
    val jvmArguments: List<String> = listOf("-Xmx2g"),
    val versionInfo: String? = null,
    val versionInfoThrows: Boolean = false,
)

internal data class RecordingBuildLauncher(
    val launcher: BuildLauncher,
    val tasks: MutableList<String>,
    val arguments: MutableList<String>,
)

internal fun defaultProxyValue(method: Method): Any? =
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

internal fun buildEnvironmentProxy(
    options: BuildEnvironmentProxyOptions = BuildEnvironmentProxyOptions(),
): BuildEnvironment {
    val gradleEnvironment = Proxy.newProxyInstance(
        GradleEnvironment::class.java.classLoader,
        arrayOf(GradleEnvironment::class.java),
        InvocationHandler { _, method: Method, _ ->
            when (method.name) {
                "getGradleVersion" -> options.gradleVersion
                "getGradleUserHome" -> options.gradleUserHome
                else -> defaultProxyValue(method)
            }
        },
    ) as GradleEnvironment

    val javaEnvironment = Proxy.newProxyInstance(
        JavaEnvironment::class.java.classLoader,
        arrayOf(JavaEnvironment::class.java),
        InvocationHandler { _, method: Method, _ ->
            when (method.name) {
                "getJavaHome" -> File(options.javaHome)
                "getJvmArguments" -> options.jvmArguments
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
                "getVersionInfo" ->
                    if (options.versionInfoThrows) {
                        throw UnsupportedMethodException("getVersionInfo")
                    } else {
                        options.versionInfo
                    }
                else -> defaultProxyValue(method)
            }
        },
    ) as BuildEnvironment
}

internal fun recordingBuildLauncher(
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

internal fun projectConnectionProxy(
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

private fun normalizeStrings(args: Array<out Any?>?): List<String> =
    args?.flatMap { value ->
        when (value) {
            is Array<*> -> value.filterIsInstance<String>()
            is Iterable<*> -> value.filterIsInstance<String>()
            is String -> listOf(value)
            else -> emptyList()
        }
    }.orEmpty()
