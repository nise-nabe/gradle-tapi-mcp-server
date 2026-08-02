package com.example.gradle.mcp.build

import com.example.gradle.mcp.support.gradleJvmTestTaskProxy
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.gradleTaskProxy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestTaskDiscoveryTest {
    @Test
    fun `collectJvmTestTaskPaths returns verification tasks named test or ending with Test`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(
                        gradleJvmTestTaskProxy(projectPath = ":app"),
                        gradleJvmTestTaskProxy(name = "fastTest", projectPath = ":app"),
                        gradleTaskProxy(name = "check", path = ":app:check", group = "verification"),
                        gradleTaskProxy(name = "compileJava", path = ":app:compileJava", group = "build"),
                    ),
                ),
                gradleProjectProxy(
                    name = "lib",
                    path = ":lib",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":lib")),
                ),
            ),
        )

        TestTaskDiscovery.collectJvmTestTaskPaths(project) shouldBe listOf(
            ":app:fastTest",
            ":app:test",
            ":lib:test",
        )
    }

    @Test
    fun `inferTaskPath returns sole test task in multi-project build`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                ),
                gradleProjectProxy(
                    name = "lib",
                    path = ":lib",
                    tasks = emptyList(),
                ),
            ),
        )

        TestTaskDiscovery.inferTaskPath(project, listOf("com.example.app.FooTest")) shouldBe ":app:test"
        TestTaskDiscovery.inferTaskPath(project, listOf("com.lib.WrongModuleTest")) shouldBe null
    }

    @Test
    fun `inferTaskPath rejects unscoped class when sole test task package does not match`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                ),
            ),
        )

        TestTaskDiscovery.inferTaskPath(project, listOf("com.lib.WrongModuleTest")).shouldBe(null)
    }

    @Test
    fun `inferTaskPath matches class package to subproject path`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "plugin-route-collectors",
                    path = ":plugin-route-collectors",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":plugin-route-collectors")),
                ),
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":plugin")),
                ),
            ),
        )

        TestTaskDiscovery.inferTaskPath(
            project,
            listOf("com.linecorp.intellij.plugins.armeria.explorer.FooTest"),
        ).shouldBe(null)

        TestTaskDiscovery.inferTaskPath(
            project,
            listOf("com.example.plugin.route.collectors.FooTest"),
        ) shouldBe ":plugin-route-collectors:test"
    }

    @Test
    fun `inferTaskPath returns null when multiple test tasks exist in matched project`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    tasks = listOf(
                        gradleJvmTestTaskProxy(projectPath = ":plugin"),
                        gradleJvmTestTaskProxy(name = "fastTest", projectPath = ":plugin"),
                    ),
                ),
            ),
        )

        TestTaskDiscovery.inferTaskPath(
            project,
            listOf("com.example.plugin.FooTest"),
        ).shouldBe(null)
    }

    @Test
    fun `inferTaskPath returns null when only some classes match a subproject`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                ),
                gradleProjectProxy(
                    name = "lib",
                    path = ":lib",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":lib")),
                ),
            ),
        )

        TestTaskDiscovery.inferTaskPath(
            project,
            listOf(
                "com.example.app.FooTest",
                "com.unrelated.OtherTest",
            ),
        ).shouldBe(null)
    }

    @Test
    fun `compareGradleTaskPathsNaturally orders numeric module segments`() {
        TestTaskDiscovery.compareGradleTaskPathsNaturally(":mod2:test", ":mod10:test") shouldBe -1
        TestTaskDiscovery.compareGradleTaskPathsNaturally(":mod10:test", ":mod2:test") shouldBe 1
    }

    @Test
    fun `collectJvmTestTaskPaths sorts modules in natural order`() {
        val project = gradleProjectProxy(
            children = listOf(10, 2, 1).map { index ->
                gradleProjectProxy(
                    name = "mod$index",
                    path = ":mod$index",
                    tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":mod$index")),
                )
            },
        )

        TestTaskDiscovery.collectJvmTestTaskPaths(project) shouldBe listOf(
            ":mod1:test",
            ":mod2:test",
            ":mod10:test",
        )
    }

    @Test
    fun `isJvmTestTask accepts test and fastTest in verification group`() {
        TestTaskDiscovery.isJvmTestTask(gradleJvmTestTaskProxy(projectPath = ":app")).shouldBe(true)
        TestTaskDiscovery.isJvmTestTask(gradleJvmTestTaskProxy(name = "fastTest", projectPath = ":app")).shouldBe(true)
        TestTaskDiscovery.isJvmTestTask(gradleTaskProxy(name = "check", path = ":app:check", group = "verification"))
            .shouldBe(false)
    }
}
