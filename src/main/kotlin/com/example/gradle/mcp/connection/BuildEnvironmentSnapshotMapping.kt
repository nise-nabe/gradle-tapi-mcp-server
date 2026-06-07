package com.example.gradle.mcp.connection

import org.gradle.tooling.model.build.BuildEnvironment

fun buildEnvironmentSnapshotFrom(environment: BuildEnvironment): BuildEnvironmentSnapshot {
    val javaHome = environment.java.javaHome
    return BuildEnvironmentSnapshot(
        gradleVersion = environment.gradle.gradleVersion,
        gradleUserHome = environment.gradle.gradleUserHome?.absolutePath,
        javaHome = javaHome?.absolutePath,
        javaVersion = JavaVersionResolver.resolve(javaHome),
        jvmArguments = environment.java.jvmArguments,
    )
}

fun BuildEnvironmentSnapshot.toMap(): Map<String, Any?> =
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
