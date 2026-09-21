package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.dependency.mcp.DependencySourcesFacade

interface ConnectionScope {
    val connectionManager: GradleConnectionManager
}

interface BuildScope {
    val buildExecutionManager: BuildExecutionManager
}

interface DependencySourcesScope {
    val dependencySourcesFacade: DependencySourcesFacade
}

interface GradleMcpRuntime : ConnectionScope, BuildScope, DependencySourcesScope {
    /** Closes the dependency-sources facade only when it was actually used. */
    fun shutdownDependencySources()
}

class DefaultGradleMcpRuntime(
    override val connectionManager: GradleConnectionManager,
    override val buildExecutionManager: BuildExecutionManager,
    dependencySourcesFacadeFactory: () -> DependencySourcesFacade = ::DependencySourcesFacade,
) : GradleMcpRuntime {
    private val facadeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, dependencySourcesFacadeFactory)

    override val dependencySourcesFacade: DependencySourcesFacade by facadeDelegate

    override fun shutdownDependencySources() {
        if (facadeDelegate.isInitialized()) {
            facadeDelegate.value.close()
        }
    }
}
