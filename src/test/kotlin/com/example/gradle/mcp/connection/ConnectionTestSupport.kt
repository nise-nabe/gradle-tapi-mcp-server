package com.example.gradle.mcp.connection

import com.example.gradle.mcp.build.noopProjectConnection
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

internal fun getModelCountingConnection(getModelCalls: AtomicInteger = AtomicInteger(0)): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, method, _ ->
            if (method.name == "getModel") {
                getModelCalls.incrementAndGet()
            }
            null
        },
    ) as ProjectConnection

internal fun GradleConnectionManager.seedNoopConnections(vararg projects: File) {
    val connection = noopProjectConnection()
    projects.forEach { seedConnectionForTests(connection, projectDirectory = it) }
}

internal fun GradleConnectionManager.seedCountingConnections(vararg projects: File) {
    seedCountingConnections(AtomicInteger(0), *projects)
}

internal fun GradleConnectionManager.seedCountingConnections(
    getModelCalls: AtomicInteger,
    vararg projects: File,
) {
    val connection = getModelCountingConnection(getModelCalls)
    projects.forEach { seedConnectionForTests(connection, projectDirectory = it) }
}

internal fun Map<String, Any?>.statusBool(key: String): Boolean = this[key] as Boolean

internal fun Map<String, Any?>.statusStr(key: String): String? = this[key] as String?
