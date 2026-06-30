package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.support.defaultProxyReturn
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal fun runningTracker(label: String = "Gradle tasks: build"): BuildProgressTracker =
    BuildProgressTracker().also { it.markStarting(label) }

internal fun succeededTracker(label: String = "Gradle tasks: build"): BuildProgressTracker =
    runningTracker(label).also { it.markSucceeded() }

internal fun failedTracker(
    label: String = "Gradle tasks: build",
    message: String = "Build failed",
): BuildProgressTracker =
    runningTracker(label).also { it.markFailed(message) }

internal fun cancelledTracker(
    label: String = "Gradle tasks: build",
    message: String = "already done",
): BuildProgressTracker =
    runningTracker(label).also { it.markCancelled(message) }

internal fun testBuildRecord(
    id: String,
    kind: BuildKind = BuildKind.TASKS,
    tasks: List<String> = listOf("build"),
    startedAt: Instant = Instant.now(),
    tracker: BuildProgressTracker = runningTracker(),
    streams: CapturingStreams = CapturingStreams(),
    projectDirectory: String? = null,
    cancellationTokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource(),
    configure: BuildRecord.() -> Unit = {},
): BuildRecord =
    BuildRecord(
        id = id,
        kind = kind,
        tasks = tasks,
        startedAt = startedAt,
        progressTracker = tracker,
        streams = streams,
        projectDirectory = projectDirectory,
        cancellationTokenSource = cancellationTokenSource,
    ).also(configure)

internal fun BuildExecutionManager.seedTestBuild(record: BuildRecord) {
    seedRunningBuildForTests(record)
}

internal fun testCompletedSnapshot(
    buildId: String,
    projectDirectory: String,
    kind: BuildKind = BuildKind.TASKS,
    tasks: List<String> = listOf("build"),
    testClasses: List<String> = emptyList(),
    outcome: String = "SUCCESS",
    stdout: String = "BUILD SUCCESSFUL in 1s\n",
    finishedAt: Instant = Instant.now(),
): CompletedBuildSnapshot =
    CompletedBuildSnapshot(
        buildId = buildId,
        kind = kind,
        tasks = tasks,
        testClasses = testClasses,
        finishedAt = finishedAt,
        outcome = outcome,
        stdout = stdout,
        projectDirectory = projectDirectory,
    )

internal fun BuildExecutionManager.testExecutor(): ExecutorService =
    BuildExecutionManager::class.java
        .getDeclaredField("executor")
        .apply { isAccessible = true }
        .get(this) as ExecutorService

internal fun noopProjectConnection(): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, method, _ -> defaultProxyReturn(method) },
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
