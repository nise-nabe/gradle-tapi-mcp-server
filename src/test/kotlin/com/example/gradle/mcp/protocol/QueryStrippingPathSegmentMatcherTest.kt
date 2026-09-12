package com.example.gradle.mcp.protocol

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import org.junit.jupiter.api.Test

class QueryStrippingPathSegmentMatcherTest {
    @Test
    fun `matches overview uri when query is present`() {
        val matcher = QueryStrippingPathSegmentMatcher(
            ResourceTemplate(
                uriTemplate = GradleTapiResourceTemplates.overview.uriTemplate,
                name = GradleTapiResourceTemplates.overview.name,
            ),
        )

        val match = matcher.match(
            "gradle-tapi://%2Fworkspace/overview?projectPath=%3Aplugin",
        )

        match.shouldNotBeNull()
        match.variables.shouldContainExactly(mapOf("projectRoot" to "/workspace"))
    }

    @Test
    fun `matches connection status with refresh query`() {
        val matcher = QueryStrippingPathSegmentMatcher(
            ResourceTemplate(
                uriTemplate = GradleTapiResourceTemplates.connectionStatus.uriTemplate,
                name = GradleTapiResourceTemplates.connectionStatus.name,
            ),
        )

        val match = matcher.match(
            "gradle-tapi://%2Ftmp%2Fapp/connection/status?refresh=true",
        )

        match.shouldNotBeNull()
        match.variables["projectRoot"] shouldBe "/tmp/app"
    }

    @Test
    fun `does not match a different resource path`() {
        val matcher = QueryStrippingPathSegmentMatcher(
            ResourceTemplate(
                uriTemplate = GradleTapiResourceTemplates.environment.uriTemplate,
                name = GradleTapiResourceTemplates.environment.name,
            ),
        )

        matcher.match("gradle-tapi://%2Fworkspace/overview").shouldBeNull()
    }

    @Test
    fun `matches build status template`() {
        val matcher = QueryStrippingPathSegmentMatcher(
            ResourceTemplate(
                uriTemplate = GradleTapiResourceTemplates.buildStatus.uriTemplate,
                name = GradleTapiResourceTemplates.buildStatus.name,
            ),
        )

        val match = matcher.match(
            "gradle-tapi://%2Fworkspace/builds/run-1/status?includeOutput=true",
        )

        match.shouldNotBeNull()
        match.variables.shouldContainExactly(
            mapOf(
                "projectRoot" to "/workspace",
                "buildId" to "run-1",
            ),
        )
    }

    @Test
    fun `stripQueryAndFragment keeps the path`() {
        QueryStrippingPathSegmentMatcher.stripQueryAndFragment(
            "gradle-tapi://%2Fworkspace/overview?projectPath=%3Aplugin#unused",
        ) shouldBe "gradle-tapi://%2Fworkspace/overview"
    }
}
