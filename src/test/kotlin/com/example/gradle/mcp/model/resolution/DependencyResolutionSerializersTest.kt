package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.resolution.DefaultMcpConfigurationSummary
import com.example.gradle.mcp.resolution.DefaultMcpDependencyResolution
import com.example.gradle.mcp.resolution.DefaultMcpOutgoingArtifact
import com.example.gradle.mcp.resolution.DefaultMcpOutgoingVariant
import com.example.gradle.mcp.resolution.DefaultMcpResolvedComponent
import com.example.gradle.mcp.resolution.DefaultMcpResolvedComponentIdentity
import com.example.gradle.mcp.resolution.DefaultMcpResolvedDependencyEdge
import com.example.gradle.mcp.resolution.DefaultMcpSelectionReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DependencyResolutionSerializersTest {
    @Test
    fun `serializes resolution model to token-efficient map`() {
        val reason = DefaultMcpSelectionReason(
            listOf("conflict resolution"),
            true,
            false,
            false,
            false,
            false,
            false,
        )
        val root = DefaultMcpResolvedComponentIdentity("project :", null, ":", null)
        val selected = DefaultMcpResolvedComponentIdentity(
            "com.example:lib:1.0",
            "com.example",
            "lib",
            "1.0",
        )
        val model = DefaultMcpDependencyResolution(
            ":",
            "runtimeClasspath",
            "lib",
            root,
            listOf(DefaultMcpResolvedComponent(selected, "runtime", reason)),
            listOf(
                DefaultMcpResolvedDependencyEdge(
                    root,
                    "com.example:lib:1.0",
                    selected,
                    true,
                    null,
                    reason,
                ),
            ),
            false,
            false,
            1,
            1,
        )

        val map = DependencyResolutionSerializers.toMap(model)
        map["projectPath"] shouldBe ":"
        map["configuration"] shouldBe "runtimeClasspath"
        map["dependencyFilter"] shouldBe "lib"
        map["totalDependencyCount"] shouldBe 1
        (map["components"] as List<*>).size shouldBe 1
        @Suppress("UNCHECKED_CAST")
        val deps = map["dependencies"] as List<Map<String, Any?>>
        deps.single()["requested"] shouldBe "com.example:lib:1.0"
        map.containsKey("configurations") shouldBe false
    }

    @Test
    fun `serializes catalog without graph fields`() {
        val model = DefaultMcpDependencyResolution.catalog(
            ":",
            listOf(
                DefaultMcpConfigurationSummary(
                    "runtimeClasspath",
                    true,
                    false,
                    "Runtime classpath of source set 'main'.",
                    mapOf("org.gradle.usage" to "java-runtime"),
                    listOf(
                        DefaultMcpOutgoingVariant(
                            "runtimeElements",
                            mapOf("org.gradle.usage" to "java-runtime"),
                            listOf("com.example:app:1.0"),
                            listOf(DefaultMcpOutgoingArtifact("app", "jar", null, "jar")),
                        ),
                    ),
                ),
                DefaultMcpConfigurationSummary(
                    "apiElements",
                    false,
                    true,
                    null,
                    emptyMap(),
                    emptyList(),
                ),
            ),
            false,
            2,
        )

        val map = DependencyResolutionSerializers.toMap(model)
        map["projectPath"] shouldBe ":"
        map.containsKey("configuration") shouldBe false
        map.containsKey("root") shouldBe false
        map["configurationCount"] shouldBe 2
        map["configurationsTruncated"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        val configurations = map["configurations"] as List<Map<String, Any?>>
        configurations[0]["name"] shouldBe "runtimeClasspath"
        configurations[0]["canBeResolved"] shouldBe true
        configurations[0]["description"] shouldBe "Runtime classpath of source set 'main'."
        configurations[0]["attributes"] shouldBe mapOf("org.gradle.usage" to "java-runtime")
        configurations[1]["name"] shouldBe "apiElements"
        configurations[1].containsKey("attributes") shouldBe false
        configurations[1].containsKey("outgoingVariants") shouldBe false
    }
}
