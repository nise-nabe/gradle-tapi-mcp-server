package com.example.gradle.mcp.connection

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.GradleEnvironment
import org.gradle.tooling.model.build.JavaEnvironment
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class BuildEnvironmentSnapshotTest {
    @Test
    fun `toMap matches gradle_get_build_environment shape`() {
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "8.14",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = listOf("-Xmx2g"),
        )

        snapshot.toMap() shouldBe mapOf(
            "gradle" to mapOf(
                "gradleVersion" to "8.14",
                "gradleUserHome" to "/gradle/home",
            ),
            "java" to mapOf(
                "javaHome" to "/jdk/home",
                "javaVersion" to "21.0.2",
                "jvmArguments" to listOf("-Xmx2g"),
            ),
        )
    }

    @Test
    fun `toMap includes versionInfo under gradle when present`() {
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "9.6",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = emptyList(),
            versionInfo = "Gradle 9.6\nBuild time: 2026-01-01",
        )

        snapshot.toMap() shouldBe mapOf(
            "gradle" to mapOf(
                "gradleVersion" to "9.6",
                "gradleUserHome" to "/gradle/home",
                "versionInfo" to "Gradle 9.6\nBuild time: 2026-01-01",
            ),
            "java" to mapOf(
                "javaHome" to "/jdk/home",
                "javaVersion" to "21.0.2",
                "jvmArguments" to emptyList<String>(),
            ),
        )
    }

    @Test
    fun `buildEnvironmentSnapshotFrom maps versionInfo when supported`() {
        val environment = buildEnvironmentProxy(versionInfo = "Gradle 9.6")

        val snapshot = buildEnvironmentSnapshotFrom(environment)

        snapshot.versionInfo shouldBe "Gradle 9.6"
    }

    @Test
    fun `buildEnvironmentSnapshotFrom omits versionInfo when unsupported`() {
        val environment = buildEnvironmentProxy(versionInfoThrows = true)

        val snapshot = buildEnvironmentSnapshotFrom(environment)

        snapshot.versionInfo.shouldBeNull()
    }

    private fun buildEnvironmentProxy(
        versionInfo: String? = null,
        versionInfoThrows: Boolean = false,
    ): BuildEnvironment {
        val gradleEnvironment = Proxy.newProxyInstance(
            GradleEnvironment::class.java.classLoader,
            arrayOf(GradleEnvironment::class.java),
            InvocationHandler { _, method: Method, _ ->
                when (method.name) {
                    "getGradleVersion" -> "9.6"
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
                    "getJavaHome" -> File("/jdk/home")
                    "getJvmArguments" -> listOf("-Xmx2g")
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
                        if (versionInfoThrows) {
                            throw UnsupportedMethodException("getVersionInfo")
                        } else {
                            versionInfo
                        }
                    else -> defaultProxyValue(method)
                }
            },
        ) as BuildEnvironment
    }

    private fun defaultProxyValue(method: Method): Any? =
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            List::class.java -> emptyList<Any>()
            else -> null
        }
}
