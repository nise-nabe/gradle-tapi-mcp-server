package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.GradleConnectionManager
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun noopProjectConnection(): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, _, _ -> null },
    ) as ProjectConnection

internal fun GradleConnectionManager.seedNoopConnection(projectDirectory: File? = null) {
    seedConnectionForTests(
        connection = noopProjectConnection(),
        projectDirectory = projectDirectory ?: File("."),
    )
}

internal fun blockingProjectConnection(
    buildEntered: CountDownLatch,
    releaseBuild: CountDownLatch,
): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "newBuild" -> chainingProxy(
                    Class.forName("org.gradle.tooling.BuildLauncher"),
                    onRun = {
                        buildEntered.countDown()
                        releaseBuild.await(5, TimeUnit.SECONDS)
                    },
                )
                else -> defaultProxyReturn(method)
            }
        },
    ) as ProjectConnection

private fun chainingProxy(
    interfaceClass: Class<*>,
    onRun: () -> Unit,
): Any {
    val self = arrayOfNulls<Any>(1)
    self[0] = Proxy.newProxyInstance(
        interfaceClass.classLoader,
        arrayOf(interfaceClass),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "run" -> {
                    onRun()
                    null
                }
                else -> self[0]
            }
        },
    )
    return self[0]!!
}

private fun defaultProxyReturn(method: Method): Any? =
    when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE -> 0
        java.lang.Double.TYPE, java.lang.Float.TYPE -> 0
        else -> null
    }
