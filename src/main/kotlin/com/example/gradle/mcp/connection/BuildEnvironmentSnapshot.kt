package com.example.gradle.mcp.connection

import org.gradle.tooling.model.build.BuildEnvironment

data class BuildEnvironmentSnapshot(
    val gradleVersion: String,
    val gradleUserHome: String?,
    val javaHome: String?,
    val javaVersion: String?,
    val jvmArguments: List<String>,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "gradle" to mapOf(
                "gradleVersion" to gradleVersion,
                "gradleUserHome" to gradleUserHome,
            ),
            "java" to mapOf(
                "javaHome" to javaHome,
                "javaVersion" to javaVersion,
                "jvmArguments" to jvmArguments,
            ),
        )

    companion object {
        fun from(environment: BuildEnvironment): BuildEnvironmentSnapshot {
            val javaHome = environment.java.javaHome
            return BuildEnvironmentSnapshot(
                gradleVersion = environment.gradle.gradleVersion,
                gradleUserHome = environment.gradle.gradleUserHome?.absolutePath,
                javaHome = javaHome?.absolutePath,
                javaVersion = JavaVersionResolver.resolve(javaHome),
                jvmArguments = environment.java.jvmArguments,
            )
        }
    }
}
