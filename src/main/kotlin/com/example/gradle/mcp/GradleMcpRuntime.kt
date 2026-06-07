package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager

interface ConnectionScope {
    val connectionManager: GradleConnectionManager
}

interface BuildScope {
    val buildExecutionManager: BuildExecutionManager
}

interface GradleMcpRuntime : ConnectionScope, BuildScope

class DefaultGradleMcpRuntime(
    override val connectionManager: GradleConnectionManager,
    override val buildExecutionManager: BuildExecutionManager,
) : GradleMcpRuntime
