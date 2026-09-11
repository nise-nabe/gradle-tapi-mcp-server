package com.example.gradle.mcp.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import org.junit.jupiter.api.Test

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
    }
}
