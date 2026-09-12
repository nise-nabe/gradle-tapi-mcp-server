package com.example.gradle.mcp.model

import com.example.gradle.mcp.support.basicGradleProjectProxy
import com.example.gradle.mcp.support.gradleBuildProxy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.File

class BuildTreeTargetLookupTest {
    @Test
    fun `finds a project in an included build`() {
        val includedRoot = basicGradleProjectProxy(
            name = "plugins",
            path = ":",
            directory = File("/included"),
            buildTreePath = ":plugins",
        )
        val includedChild = basicGradleProjectProxy(
            name = "core",
            path = ":core",
            directory = File("/included/core"),
            buildTreePath = ":plugins:core",
        )
        val includedBuild = gradleBuildProxy(
            rootDir = File("/included"),
            rootProject = includedRoot,
            projects = listOf(includedRoot, includedChild),
        )
        val rootProject = basicGradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            buildTreePath = ":",
        )
        val root = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            includedBuilds = listOf(includedBuild),
        )

        BuildTreeTargetLookup.find(root, ":plugins:core").shouldBeSameInstanceAs(includedChild)
        BuildTreeTargetLookup.find(root, ":plugins").shouldBeSameInstanceAs(includedRoot)
    }

    @Test
    fun `finds buildSrc via editable builds without looping on cycles`() {
        val buildSrcRoot = basicGradleProjectProxy(
            name = "buildSrc",
            path = ":",
            directory = File("/root/buildSrc"),
            buildTreePath = ":buildSrc",
        )
        val buildSrc = gradleBuildProxy(
            rootDir = File("/root/buildSrc"),
            rootProject = buildSrcRoot,
            projects = listOf(buildSrcRoot),
        )
        val rootProject = basicGradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            buildTreePath = ":",
        )
        val root = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            editableBuilds = listOf(
                buildSrc,
                gradleBuildProxy(
                    rootDir = File("/root"),
                    rootProject = rootProject,
                    projects = listOf(rootProject),
                ),
            ),
        )

        BuildTreeTargetLookup.find(root, ":buildSrc").shouldBeSameInstanceAs(buildSrcRoot)
        BuildTreeTargetLookup.find(root, ":missing").shouldBeNull()
        BuildTreeTargetLookup.find(root, "").shouldBeNull()
    }

    @Test
    fun `does not match GradleProject path when buildTreePath differs`() {
        val includedRoot = basicGradleProjectProxy(
            name = "plugins",
            path = ":",
            directory = File("/included"),
            buildTreePath = ":plugins",
        )
        val includedBuild = gradleBuildProxy(
            rootDir = File("/included"),
            rootProject = includedRoot,
            projects = listOf(includedRoot),
        )
        val rootProject = basicGradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            buildTreePath = ":",
        )
        val root = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            includedBuilds = listOf(includedBuild),
        )

        BuildTreeTargetLookup.find(root, ":").shouldBeSameInstanceAs(rootProject)
        BuildTreeTargetLookup.find(root, ":included").shouldBeNull()
    }
}
