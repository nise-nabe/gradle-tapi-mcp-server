package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.resolution.DefaultMcpDependencyResolution
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
    }
}
