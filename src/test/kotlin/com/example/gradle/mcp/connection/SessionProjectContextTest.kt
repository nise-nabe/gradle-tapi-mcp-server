package com.example.gradle.mcp.connection

import com.example.gradle.mcp.support.withWorkspaceDirectory
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionProjectContextTest {
    @Test
    fun `workspace is the default and known project`(@TempDir workspace: File) {
        val session = SessionProjectContext(workspaceProject = workspace)

        session.defaultProject() shouldBe workspace
        session.isKnown(workspace).shouldBeTrue()
        session.knownProjects() shouldBe listOf(workspace)
    }

    @Test
    fun `without workspace and connects the session knows nothing`() {
        val session = SessionProjectContext(workspaceProject = null)

        session.defaultProject().shouldBeNull()
        session.knownProjects().shouldBe(emptyList())
    }

    @Test
    fun `last connected project becomes the session default`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val session = SessionProjectContext(workspaceProject = null)

        session.onConnected(projectA)
        session.onConnected(projectB)

        session.defaultProject() shouldBe projectB
        session.knownProjects().shouldContainExactlyInAnyOrder(projectA, projectB)
    }

    @Test
    fun `connected projects are known even with a different workspace`(
        @TempDir workspace: File,
        @TempDir connected: File,
    ) {
        val session = SessionProjectContext(workspaceProject = workspace)
        session.onConnected(connected)

        session.isKnown(connected).shouldBeTrue()
        session.isKnown(workspace).shouldBeTrue()
        session.defaultProject() shouldBe connected
    }

    @Test
    fun `unknown project is not known`(@TempDir projectA: File, @TempDir projectB: File) {
        val session = SessionProjectContext(workspaceProject = null)
        session.onConnected(projectA)

        session.isKnown(projectB).shouldBeFalse()
    }

    @Test
    fun `disconnecting the default falls back to workspace`(
        @TempDir workspace: File,
        @TempDir connected: File,
    ) {
        val session = SessionProjectContext(workspaceProject = workspace)
        session.onConnected(connected)

        session.onDisconnected(connected)

        session.isKnown(connected).shouldBeFalse()
        session.defaultProject() shouldBe workspace
    }

    @Test
    fun `disconnecting everything clears held projects and restores workspace default`(
        @TempDir workspace: File,
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val session = SessionProjectContext(workspaceProject = workspace)
        session.onConnected(projectA)
        session.onConnected(projectB)

        session.onDisconnected(null)

        session.isKnown(projectA).shouldBeFalse()
        session.isKnown(projectB).shouldBeFalse()
        session.isKnown(workspace).shouldBeTrue()
        session.defaultProject() shouldBe workspace
    }

    @Test
    fun `seed project directory becomes known and default`(@TempDir seed: File) {
        val session = SessionProjectContext(
            workspaceProject = null,
            seedProjectDirectories = listOf(seed),
        )

        session.isKnown(seed).shouldBeTrue()
        session.defaultProject() shouldBe seed
    }

    @Test
    fun `isKnown matches canonical equivalents`(@TempDir project: File) {
        val session = SessionProjectContext(workspaceProject = null)
        session.onConnected(project)

        val equivalent = File(project.path + File.separator + ".").absoluteFile
        session.isKnown(equivalent).shouldBeTrue()
    }
}
