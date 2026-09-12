package com.example.gradle.mcp.model

import com.example.gradle.mcp.support.basicGradleProjectProxy
import com.example.gradle.mcp.support.gradleBuildProxy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.GradleBuild
import org.junit.jupiter.api.Test
import java.io.File

class FetchToolingModelActionTest {
    @Test
    fun `copies fetch result model and failures`() {
        val project = sampleGradleProject()
        val controller = buildControllerProxy(
            mapOf(
                GradleProject::class.java to fetchModelResultProxy(
                    project,
                    listOf(toolingFailureProxy("included build failed", "buildSrc")),
                ),
            ),
        )

        val payload = FetchToolingModelAction(GradleProject::class.java).execute(controller)

        payload.model.shouldBeSameInstanceAs(project)
        payload.failures.shouldHaveSize(1)
        payload.failures.single().message shouldBe "included build failed"
        payload.failures.single().description shouldBe "buildSrc"
        payload.isFailuresTruncated shouldBe false
        payload.unresolvedBuildTreePath.shouldBeNull()
    }

    @Test
    fun `default action fetches by type without a target`() {
        val project = sampleGradleProject()
        val fetchCalls = mutableListOf<Pair<Any?, Class<*>>>()
        val controller = buildControllerProxy(
            resultsByType = mapOf(GradleProject::class.java to fetchModelResultProxy(project)),
            fetchCalls = fetchCalls,
        )

        FetchToolingModelAction(GradleProject::class.java).execute(controller)

        fetchCalls shouldBe listOf(null to GradleProject::class.java)
    }

    @Test
    fun `fetches included build project by buildTreePath`() {
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
        val gradleBuild = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            includedBuilds = listOf(includedBuild),
        )
        val includedGradleProject = sampleGradleProject()
        val fetchCalls = mutableListOf<Pair<Any?, Class<*>>>()
        val controller = buildControllerProxy(
            resultsByType = mapOf(
                GradleBuild::class.java to fetchModelResultProxy(
                    gradleBuild,
                    listOf(toolingFailureProxy("settings failed in another included build")),
                ),
            ),
            targetedResults = mapOf(
                (includedRoot to GradleProject::class.java) to fetchModelResultProxy(
                    includedGradleProject,
                    listOf(toolingFailureProxy("target fetch failed")),
                ),
            ),
            fetchCalls = fetchCalls,
        )

        val payload = FetchToolingModelAction(GradleProject::class.java, ":plugins").execute(controller)

        payload.model.shouldBeSameInstanceAs(includedGradleProject)
        payload.failures.map { it.message } shouldBe listOf(
            "target fetch failed",
            "settings failed in another included build",
        )
        payload.unresolvedBuildTreePath.shouldBeNull()
        fetchCalls shouldBe listOf(
            null to GradleBuild::class.java,
            includedRoot to GradleProject::class.java,
        )
    }

    @Test
    fun `fetches buildSrc project from editable builds`() {
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
        val gradleBuild = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            editableBuilds = listOf(buildSrc),
        )
        val buildSrcProject = sampleGradleProject()
        val controller = buildControllerProxy(
            resultsByType = mapOf(GradleBuild::class.java to fetchModelResultProxy(gradleBuild)),
            targetedResults = mapOf(
                (buildSrcRoot to GradleProject::class.java) to fetchModelResultProxy(buildSrcProject),
            ),
        )

        val payload = FetchToolingModelAction(GradleProject::class.java, ":buildSrc").execute(controller)

        payload.model.shouldBeSameInstanceAs(buildSrcProject)
    }

    @Test
    fun `records unresolved buildTreePath when the target is missing`() {
        val rootProject = basicGradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            buildTreePath = ":",
        )
        val gradleBuild = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
        )
        val controller = buildControllerProxy(
            mapOf(
                GradleBuild::class.java to fetchModelResultProxy(
                    gradleBuild,
                    listOf(toolingFailureProxy("included build failed")),
                ),
            ),
        )

        val payload = FetchToolingModelAction(GradleProject::class.java, ":missing").execute(controller)

        payload.model.shouldBeNull()
        payload.unresolvedBuildTreePath shouldBe ":missing"
        payload.failures.single().message shouldBe "included build failed"
    }

    @Test
    fun `dual action merges project and invocations failures`() {
        val project = sampleGradleProject()
        val invocations = sampleBuildInvocations()
        val controller = buildControllerProxy(
            mapOf(
                GradleProject::class.java to fetchModelResultProxy(
                    project,
                    listOf(toolingFailureProxy("project failed")),
                ),
                BuildInvocations::class.java to fetchModelResultProxy(
                    invocations,
                    listOf(toolingFailureProxy("invocations failed")),
                ),
            ),
        )

        val payload = FetchProjectAndInvocationsAction().execute(controller)

        payload.project.shouldBeInstanceOf<GradleProject>()
        payload.invocations.shouldBeInstanceOf<BuildInvocations>()
        payload.failures.map { it.message } shouldBe listOf("project failed", "invocations failed")
        payload.unresolvedBuildTreePath.shouldBeNull()
    }

    @Test
    fun `dual action fetches targeted project and invocations`() {
        val includedRoot = basicGradleProjectProxy(
            name = "lib",
            path = ":",
            directory = File("/lib"),
            buildTreePath = ":lib",
        )
        val includedBuild = gradleBuildProxy(
            rootDir = File("/lib"),
            rootProject = includedRoot,
            projects = listOf(includedRoot),
        )
        val rootProject = basicGradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            buildTreePath = ":",
        )
        val gradleBuild = gradleBuildProxy(
            rootDir = File("/root"),
            rootProject = rootProject,
            projects = listOf(rootProject),
            includedBuilds = listOf(includedBuild),
        )
        val project = sampleGradleProject()
        val invocations = sampleBuildInvocations()
        val fetchCalls = mutableListOf<Pair<Any?, Class<*>>>()
        val controller = buildControllerProxy(
            resultsByType = mapOf(GradleBuild::class.java to fetchModelResultProxy(gradleBuild)),
            targetedResults = mapOf(
                (includedRoot to GradleProject::class.java) to fetchModelResultProxy(project),
                (includedRoot to BuildInvocations::class.java) to fetchModelResultProxy(invocations),
            ),
            fetchCalls = fetchCalls,
        )

        val payload = FetchProjectAndInvocationsAction(":lib").execute(controller)

        payload.project.shouldBeSameInstanceAs(project)
        payload.invocations.shouldBeSameInstanceAs(invocations)
        fetchCalls shouldBe listOf(
            null to GradleBuild::class.java,
            includedRoot to GradleProject::class.java,
            includedRoot to BuildInvocations::class.java,
        )
    }
}
