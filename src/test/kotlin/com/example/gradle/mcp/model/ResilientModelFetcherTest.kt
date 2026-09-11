package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.Supplier
import org.gradle.tooling.model.GradleProject
import org.junit.jupiter.api.Test

class ResilientModelFetcherTest {
    @Test
    fun `uses getModel when Gradle is older than 9_3`() {
        val harness = phasedConnection(
            payload = null,
            directModels = mapOf(GradleProject::class.java to sampleGradleProject()),
        )

        val result = harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            emptyList(),
            "8.14",
        )

        result.usedFallback shouldBe true
        result.partial shouldBe false
        result.model.path shouldBe ":"
        harness.calls.shouldContainExactly(listOf("getModel:GradleProject"))
    }

    @Test
    fun `returns partial model when handler receives failures and run succeeds`() {
        val payload = ResilientModelPayload(
            sampleGradleProject(),
            FailureRecords.fromFailures(listOf(toolingFailureProxy("included build failed"))).records,
            false,
        )
        val harness = phasedConnection(payload)

        val result = harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            emptyList(),
            "9.7.1",
        )

        result.partial shouldBe true
        result.usedFallback shouldBe false
        result.failures.single().message shouldBe "included build failed"
        harness.calls.shouldContainExactly(listOf("action", "buildFinished", "build", "run"))
    }

    @Test
    fun `keeps handler model when run throws after delivering payload`() {
        val payload = ResilientModelPayload(sampleGradleProject(), emptyList(), false)
        val thrown = GradleConnectionException(
            "Could not fetch model of type 'GradleProject'",
            RuntimeException("root"),
            Supplier { listOf(toolingFailureProxy("included build failed")) },
        )
        val harness = phasedConnection(payload, runException = thrown)

        val result = harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            emptyList(),
            "9.7.1",
        )

        result.partial shouldBe true
        result.model.path shouldBe ":"
        result.failures.single().message shouldBe "included build failed"
    }

    @Test
    fun `dedupes payload failures against exception failures`() {
        val payload = ResilientModelPayload(
            sampleGradleProject(),
            FailureRecords.fromFailures(listOf(toolingFailureProxy("included build failed"))).records,
            false,
        )
        val thrown = GradleConnectionException(
            "Could not fetch model",
            RuntimeException("root"),
            Supplier { listOf(toolingFailureProxy("included build failed")) },
        )
        val harness = phasedConnection(payload, runException = thrown)

        val result = harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            emptyList(),
            "9.7.1",
        )

        result.failures.shouldContainExactly(
            listOf(
                FailureSnapshot(message = "included build failed", description = null),
            ),
        )
    }

    @Test
    fun `throws BUILD_FAILED when model is missing`() {
        val payload = ResilientModelPayload(
            null,
            FailureRecords.fromFailures(listOf(toolingFailureProxy("configure failed"))).records,
            false,
        )
        val harness = phasedConnection(payload)

        val error = shouldThrow<McpException> {
            harness.connection.fetchResilientModel(
                GradleProject::class.java,
                ModelFetchPhase.BUILD_FINISHED,
                emptyList(),
                "9.7.1",
            )
        }

        error.code shouldBe McpErrorCode.BUILD_FAILED
        error.message shouldContain "GradleProject"
        error.errorDetails["failures"] shouldBe listOf(mapOf("message" to "configure failed"))
    }

    @Test
    fun `uses projectsLoaded for GradleBuild`() {
        val payload = ResilientModelPayload("gradle-build", emptyList(), false)
        val harness = phasedConnection(payload)

        val result = harness.connection.fetchResilientModel(
            String::class.java,
            ModelFetchPhase.PROJECTS_LOADED,
            emptyList(),
            "9.7.1",
        )

        result.model shouldBe "gradle-build"
        harness.calls.shouldContainExactly(listOf("action", "projectsLoaded", "build", "run"))
    }

    @Test
    fun `forwards prepareTasks to the phased executer`() {
        val payload = ResilientModelPayload(sampleGradleProject(), emptyList(), false)
        val harness = phasedConnection(payload)

        harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            listOf("help", ":test"),
            "9.7.1",
        )

        harness.calls.shouldContainExactly(
            listOf("action", "buildFinished", "build", "forTasks:help,:test", "run"),
        )
    }

    @Test
    fun `falls back to getModel when version is unknown and phased action yields no payload`() {
        val project = sampleGradleProject()
        val harness = phasedConnection(
            payload = null,
            runException = GradleConnectionException("fetch is not supported"),
            directModels = mapOf(GradleProject::class.java to project),
        )

        val result = harness.connection.fetchResilientModel(
            GradleProject::class.java,
            ModelFetchPhase.BUILD_FINISHED,
            emptyList(),
            gradleVersion = null,
        )

        result.usedFallback shouldBe true
        result.model.shouldBeSameInstanceAs(project)
        harness.calls.shouldContainExactly(listOf("action", "buildFinished", "build", "run", "getModel:GradleProject"))
    }

    @Test
    fun `does not fall back when Gradle version is known and payload is missing`() {
        val harness = phasedConnection(
            payload = null,
            runException = GradleConnectionException("Could not fetch model of type 'GradleProject'"),
        )

        val error = shouldThrow<McpException> {
            harness.connection.fetchResilientModel(
                GradleProject::class.java,
                ModelFetchPhase.BUILD_FINISHED,
                emptyList(),
                "9.7.1",
            )
        }

        error.code shouldBe McpErrorCode.BUILD_FAILED
        harness.calls.shouldContainExactly(listOf("action", "buildFinished", "build", "run"))
    }

    @Test
    fun `fetches project and invocations in one buildFinished action`() {
        val payload = ResilientProjectInvocationsPayload(
            sampleGradleProject(),
            sampleBuildInvocations(),
            emptyList(),
            false,
        )
        val harness = phasedConnection(payload)

        val result = harness.connection.fetchResilientProjectAndInvocations(emptyList(), "9.7.1")

        result.model.project.path shouldBe ":"
        result.model.invocations.shouldBeSameInstanceAs(payload.invocations)
        harness.calls.shouldContainExactly(listOf("action", "buildFinished", "build", "run"))
    }

    @Test
    fun `supportsResilientFetch uses base version 9_3`() {
        ResilientModelFetcher.supportsResilientFetch("8.14") shouldBe false
        ResilientModelFetcher.supportsResilientFetch("9.2.1") shouldBe false
        ResilientModelFetcher.supportsResilientFetch("9.3") shouldBe true
        ResilientModelFetcher.supportsResilientFetch("9.3-rc-1") shouldBe true
        ResilientModelFetcher.supportsResilientFetch("9.7.1") shouldBe true
        ResilientModelFetcher.supportsResilientFetch(null) shouldBe true
        ResilientModelFetcher.supportsResilientFetch("not-a-version") shouldBe true
    }
}
