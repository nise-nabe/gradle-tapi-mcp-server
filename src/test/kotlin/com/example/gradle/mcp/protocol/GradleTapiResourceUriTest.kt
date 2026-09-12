package com.example.gradle.mcp.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GradleTapiResourceUriTest {
    @Test
    fun `round-trips nested project path with space`(@TempDir root: File) {
        val project = root.resolve("my project").also { it.mkdirs() }
        val uri = GradleTapiResourceUri(
            projectDirectory = project.absoluteFile,
            kind = GradleTapiResourceKind.ConnectionStatus,
            query = mapOf("refresh" to "true"),
        ).toUri()

        uri.shouldBe(
            "gradle-tapi://${GradleTapiResourceUri.encodeSegment(project.absoluteFile.path)}" +
                "/connection/status?refresh=true",
        )
        uri.contains(" ").shouldBe(false)
        uri.contains("+").shouldBe(false)

        val parsed = GradleTapiResourceUri.parse(uri)
        parsed.projectDirectory shouldBe project.absoluteFile
        parsed.kind shouldBe GradleTapiResourceKind.ConnectionStatus
        parsed.query.shouldContainExactly(mapOf("refresh" to "true"))
        parsed.toToolArgs()["refresh"] shouldBe true
        parsed.toToolArgs()["projectDirectory"] shouldBe project.absoluteFile.path
    }

    @Test
    fun `parses overview projectPath query`() {
        val uri = "gradle-tapi://%2Fworkspace/overview?projectPath=%3Aplugin"

        val parsed = GradleTapiResourceUri.parse(uri)

        parsed.kind shouldBe GradleTapiResourceKind.Overview
        parsed.projectDirectory shouldBe File("/workspace").absoluteFile
        parsed.query.shouldContainExactly(mapOf("projectPath" to ":plugin"))
        parsed.toToolArgs()["projectPath"] shouldBe ":plugin"
    }

    @Test
    fun `parses overview buildTreePath query`() {
        val uri = "gradle-tapi://%2Fworkspace/overview?buildTreePath=%3AbuildSrc"

        val parsed = GradleTapiResourceUri.parse(uri)

        parsed.kind shouldBe GradleTapiResourceKind.Overview
        parsed.query.shouldContainExactly(mapOf("buildTreePath" to ":buildSrc"))
        parsed.toToolArgs()["buildTreePath"] shouldBe ":buildSrc"
    }

    @Test
    fun `parses build status path and omits query by default`() {
        val parsed = GradleTapiResourceUri.parse(
            "gradle-tapi://%2Ftmp%2Fapp/builds/abc-123/status",
        )

        parsed.kind shouldBe GradleTapiResourceKind.BuildStatus("abc-123")
        parsed.query.shouldBeEmpty()
        parsed.toToolArgs()["buildId"] shouldBe "abc-123"
        parsed.toToolArgs().containsKey("includeOutput") shouldBe false
    }

    @Test
    fun `parses recent builds and integer query`() {
        val parsed = GradleTapiResourceUri.parse(
            "gradle-tapi://%2Ftmp%2Fapp/builds/recent?limit=5",
        )

        parsed.kind shouldBe GradleTapiResourceKind.RecentBuilds
        parsed.toToolArgs()["limit"] shouldBe 5
    }

    @Test
    fun `parses environment path`() {
        GradleTapiResourceUri.parse("gradle-tapi://%2Fworkspace/environment").kind shouldBe
            GradleTapiResourceKind.Environment
    }

    @Test
    fun `rejects relative encoded project root`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("gradle-tapi://rel-app/environment")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message.contains("absolute path") shouldBe true
    }

    @Test
    fun `rejects unknown path`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("gradle-tapi://%2Fworkspace/cache/status")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message.contains("Unknown gradle-tapi resource path") shouldBe true
    }

    @Test
    fun `toUri and toToolArgs use an absolute project root`() {
        val relative = File("rel-app")
        val absolute = relative.absoluteFile
        val resource = GradleTapiResourceUri(relative, GradleTapiResourceKind.Environment)

        resource.toUri() shouldBe
            "gradle-tapi://${GradleTapiResourceUri.encodeSegment(absolute.path)}/environment"
        resource.toToolArgs()["projectDirectory"] shouldBe absolute.path
        File(resource.toToolArgs()["projectDirectory"] as String).isAbsolute shouldBe true
    }

    @Test
    fun `parses scheme case-insensitively`() {
        val parsed = GradleTapiResourceUri.parse(
            "GRADLE-TAPI://%2Fworkspace/environment",
        )

        parsed.kind shouldBe GradleTapiResourceKind.Environment
        parsed.projectDirectory shouldBe File("/workspace").absoluteFile
    }

    @Test
    fun `rejects non gradle-tapi scheme`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("file:///workspace/overview")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `rejects blank uri`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("   ")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `rejects missing resource path`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("gradle-tapi://%2Fworkspace")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `rejects blank buildId`() {
        val error = shouldThrow<McpException> {
            GradleTapiResourceUri.parse("gradle-tapi://%2Fworkspace/builds/%20/status")
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `toToolArgs keeps URI projectDirectory and buildId over query aliases`() {
        val parsed = GradleTapiResourceUri.parse(
            "gradle-tapi://%2Ftmp%2Fapp/builds/real-id/status?buildId=other&projectDirectory=%2Ftmp%2Fother",
        )

        val args = parsed.toToolArgs()
        args["buildId"] shouldBe "real-id"
        args["projectDirectory"] shouldBe File("/tmp/app").absoluteFile.path
    }

    @Test
    fun `decodes plus in project root as plus not space`() {
        val parsed = GradleTapiResourceUri.parse(
            "gradle-tapi://%2Ftmp%2Ffoo+bar/environment",
        )

        parsed.projectDirectory shouldBe File("/tmp/foo+bar").absoluteFile
        parsed.kind shouldBe GradleTapiResourceKind.Environment
    }

    @Test
    fun `rejects non-boolean refresh query`() {
        val parsed = GradleTapiResourceUri.parse(
            "gradle-tapi://%2Fworkspace/connection/status?refresh=yes",
        )
        val error = shouldThrow<McpException> {
            parsed.toToolArgs()
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }
}
