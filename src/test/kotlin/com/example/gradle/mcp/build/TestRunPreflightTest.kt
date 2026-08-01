package com.example.gradle.mcp.build

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.gradleJvmTestTaskProxy
import com.example.gradle.mcp.support.gradleProjectConnectionProxy
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.testRunProjectConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.model.GradleTask
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class TestRunPreflightTest {
    private val projectDirectory = File("/workspace").absoluteFile

    @Test
    fun `preflightRunTests refetches single-project on each call because false is not cached`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options)
            preflightRunTests(projectDirectory, options)
        }

        getModelCalls.get() shouldBe 2
        connectionManager.cachedHasSubprojects(projectDirectory).shouldBeNull()
    }

    @Test
    fun `preflightRunTests loads model when multi-project is cached to include suggestedTaskPaths`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(mockVerificationTask("test", ":app:test")),
                        ),
                        gradleProjectProxy(
                            name = "lib",
                            path = ":lib",
                            tasks = listOf(mockVerificationTask("test", ":lib:test")),
                        ),
                    ),
                ),
                getModelCalls,
            ),
            projectDirectory = projectDirectory,
            cachedHasSubprojects = true,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val error = shouldThrow<McpException> {
            with(runtime) {
                preflightRunTests(
                    projectDirectory,
                    TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                )
            }
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails["suggestedTaskPaths"] shouldBe listOf(":app:test", ":lib:test")
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `preflightRunTests rejects unscoped classes in multi-project builds with suggestions`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(mockVerificationTask("test", ":app:test")),
                        ),
                        gradleProjectProxy(
                            name = "lib",
                            path = ":lib",
                            tasks = listOf(mockVerificationTask("test", ":lib:test")),
                        ),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val error = shouldThrow<McpException> {
            with(runtime) {
                preflightRunTests(
                    projectDirectory,
                    TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                )
            }
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        (error.errorDetails["suggestedTaskPaths"] as List<*>) shouldContain ":app:test"
        (error.errorDetails["suggestedTaskPaths"] as List<*>) shouldContain ":lib:test"
        connectionManager.cachedHasSubprojects(projectDirectory) shouldBe true
    }

    @Test
    fun `preflightRunTests infers taskPath when only one test task exists`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                        ),
                        gradleProjectProxy(name = "lib", path = ":lib"),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val resolution = with(runtime) {
            preflightRunTests(
                projectDirectory,
                TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
            )
        }

        resolution.taskPathInferred shouldBe true
        resolution.options.taskPath shouldBe ":app:test"
    }

    @Test
    fun `validateProjectScope includes suggestedTaskPaths for verification tasks`() {
        val project = gradleProjectProxy(
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(mockVerificationTask("test", ":app:test")),
                ),
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    tasks = listOf(mockVerificationTask("fastTest", ":plugin:fastTest")),
                ),
            ),
        )

        val error = shouldThrow<McpException> {
            TestRunPreflight.validateProjectScope(
                TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                project,
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails["suggestedTaskPaths"] shouldBe listOf(":app:test", ":plugin:fastTest")
        error.errorDetails["hint"] shouldBe
            TestTaskDiscovery.MULTI_PROJECT_TEST_SCOPE_HINT +
            " suggestedTaskPaths lists JVM Test task paths (name test or *Test). " +
            "When truncated, use gradle_get_project_model with includeTasks=true for the full list."
    }

    @Test
    fun `collectSuggestedTestTaskPaths caps list and marks truncation`() {
        val children = (1..25).map { index ->
            gradleProjectProxy(
                name = "mod$index",
                path = ":mod$index",
                tasks = listOf(mockVerificationTask("test", ":mod$index:test")),
            )
        }
        val project = gradleProjectProxy(children = children)

        val suggestion = TestRunPreflight.collectSuggestedTestTaskPaths(project, maxPaths = 20)

        suggestion.paths shouldHaveSize 20
        suggestion.truncated shouldBe true
        suggestion.paths.all { it.endsWith(":test") && it.startsWith(":mod") } shouldBe true
    }

    @Test
    fun `validateProjectScope marks suggestedTaskPathsTruncated when capped`() {
        val children = (1..25).map { index ->
            gradleProjectProxy(
                name = "mod$index",
                path = ":mod$index",
                tasks = listOf(mockVerificationTask("test", ":mod$index:test")),
            )
        }
        val project = gradleProjectProxy(children = children)

        val error = shouldThrow<McpException> {
            TestRunPreflight.validateProjectScope(
                TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                project,
            )
        }

        (error.errorDetails["suggestedTaskPaths"] as List<*>) shouldHaveSize 20
        error.errorDetails["suggestedTaskPathsTruncated"] shouldBe true
    }

    @Test
    fun `collectSuggestedTestTaskPaths includes JVM test tasks and excludes lifecycle verification tasks`() {
        val project = gradleProjectProxy(
            path = ":",
            tasks = listOf(mockVerificationTask("check", ":check")),
            children = listOf(
                gradleProjectProxy(
                    name = "app",
                    path = ":app",
                    tasks = listOf(mockVerificationTask("test", ":app:test")),
                ),
            ),
        )

        TestRunPreflight.collectSuggestedTestTaskPaths(project).paths shouldBe listOf(":app:test")
    }

    @Test
    fun `ensureTestRunProjectScope rejects deferred multi-project run with suggestedTaskPaths`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(mockVerificationTask("test", ":app:test")),
                        ),
                        gradleProjectProxy(
                            name = "lib",
                            path = ":lib",
                            tasks = listOf(mockVerificationTask("test", ":lib:test")),
                        ),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )

        val error = shouldThrow<McpException> {
            ensureTestRunProjectScope(
                connectionManager,
                projectDirectory,
                TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails["suggestedTaskPaths"] shouldBe listOf(":app:test", ":lib:test")
    }

    @Test
    fun `preflightRunTests defers scope check when multi-project is cached and deferScopeModelCheck is true`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
            cachedHasSubprojects = true,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }

        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `preflightRunTests includes suggestedTaskPaths after model load`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(mockVerificationTask("test", ":app:test")),
                        ),
                        gradleProjectProxy(
                            name = "lib",
                            path = ":lib",
                            tasks = listOf(mockVerificationTask("test", ":lib:test")),
                        ),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val error = shouldThrow<McpException> {
            with(runtime) {
                preflightRunTests(
                    projectDirectory,
                    TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                )
            }
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.errorDetails["suggestedTaskPaths"] shouldBe listOf(":app:test", ":lib:test")
    }

    @Test
    fun `preflightRunTests skips active build guard when deferScopeModelCheck is true`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy()),
            projectDirectory = projectDirectory,
        )
        val buildManager = BuildExecutionManager(connectionManager)
        buildManager.seedRunningBuildForTests(
            com.example.gradle.mcp.support.testBuildRecord(
                id = "running-build",
                tracker = com.example.gradle.mcp.support.runningTracker(),
                projectDirectory = projectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildManager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }
    }

    @Test
    fun `preflightRunTests detects newly added subprojects when false was never cached`() {
        val getModelCalls = AtomicInteger(0)
        val singleProject = gradleProjectProxy()
        val multiProject = gradleProjectProxy(
            children = listOf(gradleProjectProxy(name = "app", path = ":app")),
        )
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                project = singleProject,
                getModelCalls = getModelCalls,
                projectSequence = listOf(singleProject, multiProject),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options)
            connectionManager.cachedHasSubprojects(projectDirectory).shouldBeNull()

            val error = shouldThrow<McpException> {
                preflightRunTests(projectDirectory, options)
            }
            error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }

        getModelCalls.get() shouldBe 2
        connectionManager.cachedHasSubprojects(projectDirectory) shouldBe true
    }

    @Test
    fun `non-deferred unscoped test run queries GradleProject once across preflight and execution`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = testRunProjectConnection(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val manager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, manager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = false)
        }
        getModelCalls.get() shouldBe 1

        runBlocking {
            manager.runForeground(
                request = BuildRunRequest(
                    projectDirectory = projectDirectory,
                    kind = BuildKind.TESTS,
                    selection = options.selection,
                    testScopeValidatedAtPreflight = true,
                ),
                notifier = null,
            )
        }

        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `deferred unscoped test run queries GradleProject at execution time`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = testRunProjectConnection(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val manager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, manager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }
        getModelCalls.get() shouldBe 0

        runBlocking {
            manager.runForeground(
                request = BuildRunRequest(
                    projectDirectory = projectDirectory,
                    kind = BuildKind.TESTS,
                    selection = options.selection,
                    testScopeValidatedAtPreflight = false,
                ),
                notifier = null,
            )
        }

        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `deferred test run infers taskPath on record at execution time`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                        ),
                        gradleProjectProxy(name = "lib", path = ":lib"),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val manager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, manager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }

        val response = runBlocking {
            manager.runForeground(
                request = BuildRunRequest(
                    projectDirectory = projectDirectory,
                    kind = BuildKind.TESTS,
                    selection = options.selection,
                    testScopeValidatedAtPreflight = false,
                ),
                notifier = null,
            )
        }

        response["taskPath"] shouldBe ":app:test"
        response["taskPathInferred"] shouldBe true
    }

    @Test
    fun `preflightRunTests infers taskPath for unscoped testMethods when only one test task exists`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(
                        gradleProjectProxy(
                            name = "app",
                            path = ":app",
                            tasks = listOf(gradleJvmTestTaskProxy(projectPath = ":app")),
                        ),
                        gradleProjectProxy(name = "lib", path = ":lib"),
                    ),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val resolution = with(runtime) {
            preflightRunTests(
                projectDirectory,
                TestRunOptions(
                    selection = TestRunSelection.Methods(
                        mapOf("com.example.FooTest" to listOf("works")),
                    ),
                ),
            )
        }

        resolution.taskPathInferred shouldBe true
        resolution.options.taskPath shouldBe ":app:test"
    }

    private fun mockVerificationTask(name: String, path: String): GradleTask =
        Proxy.newProxyInstance(
            GradleTask::class.java.classLoader,
            arrayOf(GradleTask::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "getPath" -> path
                "getGroup" -> "verification"
                "getDescription" -> null
                "getDisplayName" -> "task '$path'"
                else -> defaultProxyReturn(method)
            }
        } as GradleTask
}
