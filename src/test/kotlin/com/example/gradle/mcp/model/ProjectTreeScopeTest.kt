package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.gradleProjectProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

class ProjectTreeScopeTest {
    @Test
    fun `normalizeProjectPath accepts root and bare names`() {
        ProjectTreeScope.normalizeProjectPath("") shouldBe ":"
        ProjectTreeScope.normalizeProjectPath(":") shouldBe ":"
        ProjectTreeScope.normalizeProjectPath("  :plugin  ") shouldBe ":plugin"
        ProjectTreeScope.normalizeProjectPath("plugin") shouldBe ":plugin"
    }

    @Test
    fun `findByPath locates nested subprojects`() {
        val root = multiModuleRoot()

        ProjectTreeScope.findByPath(root, ":plugin")?.path shouldBe ":plugin"
        ProjectTreeScope.findByPath(root, "plugin-shared")?.path shouldBe ":plugin-shared"
        ProjectTreeScope.findByPath(root, ":")?.path shouldBe ":"
        ProjectTreeScope.findByPath(root, ":plugin:child")?.path shouldBe ":plugin:child"
    }

    @Test
    fun `requireProject scoped to parent includes nested descendants`() {
        val root = multiModuleRoot()

        val scoped = ProjectTreeScope.requireProject(root, ":plugin")
        scoped.path shouldBe ":plugin"
        scoped.children.map { it.path } shouldBe listOf(":plugin:child")
    }

    @Test
    fun `requireProject returns root when projectPath is omitted`() {
        val root = multiModuleRoot()

        ProjectTreeScope.requireProject(root, null).path shouldBe ":"
        ProjectTreeScope.requireProject(root, "   ").path shouldBe ":"
        ProjectTreeScope.requireProject(root, ":").path shouldBe ":"
    }

    @Test
    fun `normalizeProjectPath rejects malformed paths`() {
        val error = shouldThrow<McpException> {
            ProjectTreeScope.normalizeProjectPath("::plugin")
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "::plugin"
    }

    @Test
    fun `requireProject throws when path is unknown`() {
        val root = multiModuleRoot()

        val error = shouldThrow<McpException> {
            ProjectTreeScope.requireProject(root, ":missing")
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain ":missing"
    }

    private fun multiModuleRoot() =
        gradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            children = listOf(
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    directory = File("/root/plugin"),
                    children = listOf(
                        gradleProjectProxy(
                            name = "child",
                            path = ":plugin:child",
                            directory = File("/root/plugin/child"),
                        ),
                    ),
                ),
                gradleProjectProxy(
                    name = "shared",
                    path = ":plugin-shared",
                    directory = File("/root/shared"),
                ),
            ),
        )
}
