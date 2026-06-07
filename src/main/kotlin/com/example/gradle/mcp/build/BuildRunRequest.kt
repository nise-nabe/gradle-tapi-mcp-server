package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions

data class BuildRunRequest(
    val kind: BuildKind,
    val tasks: List<String> = emptyList(),
    val testClasses: List<String> = emptyList(),
    val arguments: List<String> = emptyList(),
    val jvmArguments: List<String> = emptyList(),
    val outputLimit: OutputLimitOptions = OutputLimitOptions(),
    val progressOptions: ProgressResponseOptions = ProgressResponseOptions(),
)
