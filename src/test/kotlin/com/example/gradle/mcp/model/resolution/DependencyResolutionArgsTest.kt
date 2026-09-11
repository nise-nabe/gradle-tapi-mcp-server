package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DependencyResolutionArgsTest {
    @Test
    fun `nonNegativeIntOrDefault uses zero when key is missing`() {
        emptyMap<String, Any>().nonNegativeIntOrDefault("maxDependencies") shouldBe 0
    }

    @Test
    fun `nonNegativeIntOrDefault accepts zero and positive ints`() {
        mapOf("maxDependencies" to 0).nonNegativeIntOrDefault("maxDependencies") shouldBe 0
        mapOf("maxDependencies" to 50).nonNegativeIntOrDefault("maxDependencies") shouldBe 50
    }

    @Test
    fun `nonNegativeIntOrDefault rejects overflow and negatives via exact conversion`() {
        shouldThrow<McpException> {
            mapOf("maxDependencies" to Long.MAX_VALUE).nonNegativeIntOrDefault("maxDependencies")
        }.code shouldBe McpErrorCode.INVALID_ARGUMENT

        shouldThrow<McpException> {
            mapOf("maxDependencies" to -1).nonNegativeIntOrDefault("maxDependencies")
        }.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `parseDependencyResolutionQuery treats omitted configuration as catalog mode`() {
        val query = parseDependencyResolutionQuery(emptyMap())

        query.configuration.shouldBeNull()
        query.includeAttributes shouldBe false
        query.includeOutgoingVariants shouldBe false
        query.maxConfigurations shouldBe 0
        query.prepareTasks.shouldBeEmpty()
    }

    @Test
    fun `parseDependencyResolutionQuery reads list-mode flags`() {
        val query = parseDependencyResolutionQuery(
            mapOf(
                "projectPath" to ":app",
                "includeAttributes" to true,
                "includeOutgoingVariants" to true,
                "maxConfigurations" to 10,
            ),
        )

        query.projectPath shouldBe ":app"
        query.configuration.shouldBeNull()
        query.includeAttributes shouldBe true
        query.includeOutgoingVariants shouldBe true
        query.maxConfigurations shouldBe 10
    }

    @Test
    fun `schema does not require configuration so agents can list names`() {
        val schema = dependencyResolutionSchema()
        schema.containsKey("required") shouldBe false
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Any>
        properties shouldContainKey "includeAttributes"
        properties shouldContainKey "includeOutgoingVariants"
        properties shouldContainKey "maxConfigurations"
    }

    @Test
    fun `parseResolvableSuffix reads names and truncation`() {
        parseResolvableSuffix(
            "Unknown configuration 'implementation' on project :app. " +
                "Omit configuration to list names. Resolvable: compileClasspath, runtimeClasspath (+4 more)",
        ) shouldBe ResolvableSuffix(listOf("compileClasspath", "runtimeClasspath"), true)

        parseResolvableSuffix("Unknown projectPath: :missing") shouldBe null
        parseResolvableSuffix("... Resolvable: (none)") shouldBe ResolvableSuffix(emptyList(), false)
    }

    @Test
    fun `mapResolutionFailure attaches suggestedConfigurations`() {
        val error = mapResolutionFailure(
            IllegalArgumentException(
                "Unknown configuration 'implementation' on project :app. " +
                    "Omit configuration to list names. Resolvable: compileClasspath, runtimeClasspath",
            ),
        )

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails["suggestedConfigurations"].shouldBe(listOf("compileClasspath", "runtimeClasspath"))
        error.errorDetails["hint"] shouldBe
            "Omit configuration on gradle_get_dependency_resolution to list resolvable and consumable names."
        error.errorDetails.containsKey("suggestedConfigurationsTruncated") shouldBe false
    }

    @Test
    fun `mapResolutionFailure does not attach configuration hints for unknown projectPath`() {
        val error = mapResolutionFailure(IllegalArgumentException("Unknown projectPath: :missing"))

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails shouldBe emptyMap()
    }

    @Test
    fun `suggested names preserve listing order`() {
        val parsed = parseResolvableSuffix("Resolvable: compileClasspath, runtimeClasspath")
        parsed.shouldNotBeNull()
        parsed.names.shouldContainExactly("compileClasspath", "runtimeClasspath")
    }
}
