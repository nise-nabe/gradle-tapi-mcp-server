package com.example.gradle.mcp.connection

import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.build.BuildEnvironment

fun buildEnvironmentSnapshotFrom(environment: BuildEnvironment): BuildEnvironmentSnapshot {
    val javaHome = environment.java.javaHome
    return BuildEnvironmentSnapshot(
        gradleVersion = environment.gradle.gradleVersion,
        gradleUserHome = environment.gradle.gradleUserHome?.absolutePath,
        javaHome = javaHome?.absolutePath,
        javaVersion = JavaVersionResolver.resolve(javaHome),
        jvmArguments = environment.java.jvmArguments,
        versionInfo = resolveVersionInfo(environment),
    )
}

private fun resolveVersionInfo(environment: BuildEnvironment): String? =
    try {
        environment.versionInfo
    } catch (_: UnsupportedMethodException) {
        null
    }

fun BuildEnvironmentSnapshot.toMap(): Map<String, Any?> =
    mapOf(
        "gradle" to buildMap {
            put("gradleVersion", gradleVersion)
            put("gradleUserHome", gradleUserHome)
            versionInfo?.let { put("versionInfo", it) }
        },
        "java" to mapOf(
            "javaHome" to javaHome,
            "javaVersion" to javaVersion,
            "jvmArguments" to jvmArguments,
        ),
    )
