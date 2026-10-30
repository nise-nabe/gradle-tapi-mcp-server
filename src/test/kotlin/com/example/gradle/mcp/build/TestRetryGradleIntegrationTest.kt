package com.example.gradle.mcp.build

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real Gradle Tooling API run against the test-retry plugin. Not a unit test:
 * [tasks.test] excludes this class; `testRetryIntegrationTest` runs it.
 */
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class TestRetryGradleIntegrationTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `test launcher retry pass is not a failure and a genuine failure still is`() {
        writeFixture(projectDir)
        GradleConnector.newConnector()
            .forProjectDirectory(projectDir.toFile())
            .useGradleVersion("9.8.2")
            .connect()
            .use { connection ->
                val passed = runClass(connection, projectDir, "com.example.FlakyTest")
                val thrown = passed.thrown
                thrown.shouldNotBeNull()
                generateSequence(thrown) { it.cause }
                    .mapNotNull { it.message }
                    .any { it.trimStart().startsWith("Test failed.") } shouldBe true
                val passedSnapshot = passed.tracker.snapshot()
                passedSnapshot.failedTests shouldBe emptyList()
                passedSnapshot.failedTestCount shouldBe 0
                passedSnapshot.flakyTests.map { it.methodName } shouldContainExactly listOf("failsThenPasses()")
                passedSnapshot.failedGradleTaskCount shouldBe 0
                passedSnapshot.testTimings.entries.map { it.methodName to it.result } shouldContainExactly listOf(
                    "failsThenPasses()" to TestMethodTiming.RESULT_FAILURE,
                    "failsThenPasses()" to TestMethodTiming.RESULT_SUCCESS,
                )
                TestRetryOutcomes.failureIsPassedOnRetry(thrown, passedSnapshot) shouldBe true

                val failed = runClass(connection, projectDir, "com.example.AlwaysFailTest")
                val failedThrown = failed.thrown
                failedThrown.shouldNotBeNull()
                val failedSnapshot = failed.tracker.snapshot()
                failedSnapshot.failedTests.map { it.methodName } shouldContainExactly listOf("alwaysFails()")
                failedSnapshot.failedTestCount shouldBe 1
                failedSnapshot.flakyTests shouldBe emptyList()
                failedSnapshot.failedGradleTaskCount shouldBe 1
                failedSnapshot.testTimings.entries.size shouldBe 2
                TestRetryOutcomes.failureIsPassedOnRetry(failedThrown, failedSnapshot) shouldBe false
            }
    }
}

private data class LaunchedTest(
    val tracker: BuildProgressTracker,
    val thrown: Throwable?,
)

private fun runClass(connection: ProjectConnection, projectDir: Path, className: String): LaunchedTest {
    Files.deleteIfExists(projectDir.resolve("build/flaky-marker"))
    val tracker = BuildProgressTracker()
    val launcher = connection.newTestLauncher().withJvmTestClasses(className)
    tracker.configureLauncher(launcher)
    val thrown = runCatching { launcher.run() }.exceptionOrNull()
    return LaunchedTest(tracker, thrown)
}

private fun writeFixture(projectDir: Path) {
    Files.writeString(
        projectDir.resolve("settings.gradle.kts"),
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                mavenCentral()
            }
        }
        rootProject.name = "retry-fixture"
        """.trimIndent(),
    )
    Files.writeString(
        projectDir.resolve("build.gradle.kts"),
        """
        plugins {
            java
            id("org.gradle.test-retry") version "1.6.4"
        }

        dependencies {
            testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }

        tasks.test {
            useJUnitPlatform()
            retry {
                maxRetries.set(1)
            }
        }
        """.trimIndent(),
    )
    val tests = projectDir.resolve("src/test/java/com/example")
    Files.createDirectories(tests)
    Files.writeString(
        tests.resolve("FlakyTest.java"),
        """
        package com.example;

        import static org.junit.jupiter.api.Assertions.fail;

        import java.nio.file.Files;
        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;

        public class FlakyTest {
            @Test
            void failsThenPasses() throws Exception {
                Path marker = Path.of("build/flaky-marker");
                if (Files.notExists(marker)) {
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, "failed-once");
                    fail("first attempt");
                }
            }
        }
        """.trimIndent(),
    )
    Files.writeString(
        tests.resolve("AlwaysFailTest.java"),
        """
        package com.example;

        import static org.junit.jupiter.api.Assertions.fail;

        import org.junit.jupiter.api.Test;

        public class AlwaysFailTest {
            @Test
            void alwaysFails() {
                fail("genuine failure");
            }
        }
        """.trimIndent(),
    )
}
