package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnknownModelException
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File

fun requireBuildEnvironmentSnapshot(
    connection: ProjectConnection,
    projectDirectory: File,
): BuildEnvironmentSnapshot =
    try {
        buildEnvironmentSnapshotFrom(connection.getModel(BuildEnvironment::class.java))
    } catch (exception: Exception) {
        if (exception is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        when (exception) {
            is UnknownModelException, is UnsupportedVersionException -> throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "BuildEnvironment is not available for ${projectDirectory.path}.",
                exception,
            )
            else -> throw exception
        }
    }

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
