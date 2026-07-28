package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import org.gradle.tooling.model.GradleProject

internal object ProjectTreeScope {
    fun normalizeProjectPath(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == ":") {
            return ":"
        }
        val normalized = if (trimmed.startsWith(":")) trimmed else ":$trimmed"
        if (normalized.contains("::") || normalized.endsWith(":")) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Invalid project path '$input'. Use Gradle paths like :plugin or plugin.",
            )
        }
        return normalized
    }

    fun findByPath(root: GradleProject, projectPath: String): GradleProject? {
        val normalized = normalizeProjectPath(projectPath)
        if (normalized == ":") {
            return root
        }
        return findByPathRecursive(root, normalized)
    }

    fun requireProject(root: GradleProject, projectPath: String?): GradleProject {
        if (projectPath.isNullOrBlank()) {
            return root
        }
        val normalized = normalizeProjectPath(projectPath)
        if (normalized == ":") {
            return root
        }
        return findByPath(root, normalized)
            ?: throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Project path '$normalized' was not found in the connected Gradle project tree.",
            )
    }

    private fun findByPathRecursive(project: GradleProject, normalizedPath: String): GradleProject? {
        if (project.path == normalizedPath) {
            return project
        }
        for (child in project.children) {
            findByPathRecursive(child, normalizedPath)?.let { return it }
        }
        return null
    }
}
