package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.assertInvalidArgument
import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.GradleProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.lang.reflect.Proxy
import java.util.AbstractSet
import java.util.stream.Stream

class TestRunOptionsTest {
    @Test
    fun `parseTestRunOptions normalizes Class method entries in testClasses`() {
        val parsed = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.FooTest.testBar")),
        )

        parsed.options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("testBar")),
        )
        parsed.selectionNormalized shouldBe true
    }

    @Test
    fun `parseTestRunOptions merges multiple Class method entries into testMethods`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf(
                    "com.example.FooTest.testOne",
                    "com.example.FooTest.testTwo",
                ),
            ),
        )

        parsed.options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("testOne", "testTwo")),
        )
        parsed.selectionNormalized shouldBe true
    }

    @Test
    fun `withTestRunResponseMetadata adds selectionNormalized when flagged`() {
        val response = withTestRunResponseMetadata(
            mapOf("status" to "running"),
            selectionNormalized = true,
        )

        response["selectionNormalized"] shouldBe true
    }

    @Test
    fun `parseTestRunOptions keeps wildcard Class method entries as classes`() {
        val parsed = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.FooTest.test*")),
        )

        parsed.options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest.test*"))
        parsed.selectionNormalized shouldBe false
    }

    @Test
    fun `parseTestRunOptions keeps class-side wildcard entries as classes`() {
        val parsed = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.Foo*.testBar")),
        )

        parsed.options.selection shouldBe TestRunSelection.Classes(listOf("com.example.Foo*.testBar"))
        parsed.selectionNormalized shouldBe false
    }

    @Test
    fun `parseTestRunOptions keeps uppercase final segment as class name`() {
        val parsed = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.FooTest.Inner")),
        )

        parsed.options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest.Inner"))
        parsed.selectionNormalized shouldBe false
    }

    @Test
    fun `parseTestRunOptions normalizes dollar inner class method entries`() {
        val parsed = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.Outer\$Inner.testMethod")),
        )

        parsed.options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.Outer\$Inner" to listOf("testMethod")),
        )
        parsed.selectionNormalized shouldBe true
    }

    @Test
    fun `withTestRunResponseMetadata omits selectionNormalized when false`() {
        val response = withTestRunResponseMetadata(
            mapOf("status" to "running"),
            selectionNormalized = false,
        )

        response.containsKey("selectionNormalized") shouldBe false
    }

    @Test
    fun `parseTestRunOptions accepts testClasses`() {
        val parsed = parseTestRunOptions(mapOf("testClasses" to listOf("com.example.FooTest")))

        parsed.options.testClasses shouldBe listOf("com.example.FooTest")
        parsed.options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest"))
    }

    @Test
    fun `parseTestRunOptions accepts testMethods map form`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("method1", "method2"),
                ),
            ),
        )

        parsed.options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1", "method2"))
        parsed.options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("method1", "method2")),
        )
    }

    @Test
    fun `parseTestRunOptions accepts testMethods array form`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testMethods" to listOf(
                    mapOf("class" to "com.example.FooTest", "methods" to listOf("method1")),
                    mapOf("class" to "com.example.BarTest", "methods" to listOf("method2")),
                ),
            ),
        )

        parsed.options.testMethods shouldBe mapOf(
            "com.example.FooTest" to listOf("method1"),
            "com.example.BarTest" to listOf("method2"),
        )
    }

    @Test
    fun `parseTestRunOptions merges includePattern and includePatterns`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "includePattern" to "com.example.*",
                "includePatterns" to listOf("com.other.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        parsed.options.includePatterns shouldBe listOf("com.example.*", "com.other.*")
        parsed.options.tasks shouldBe listOf(":app:test")
    }

    @Test
    fun `parseTestRunOptions ignores blank includePatterns entries`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "includePatterns" to listOf("", "  ", "com.example.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        parsed.options.includePatterns shouldBe listOf("com.example.*")
    }

    @Test
    fun `parseTestRunOptions ignores blank includePatterns when testClasses provided`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("com.example.FooTest"),
                "includePatterns" to listOf(""),
            ),
        )

        parsed.options.testClasses shouldBe listOf("com.example.FooTest")
        parsed.options.includePatterns shouldBe emptyList()
    }

    @Test
    fun `parseTestRunOptions ignores blank and duplicate tasks`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("com.example.FooTest"),
                "tasks" to listOf("", "  ", ":app:test", ":app:test"),
            ),
        )

        parsed.options.tasks shouldBe listOf(":app:test")
    }

    @Test
    fun `parseTestRunOptions ignores blank and duplicate testClasses`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("", "  ", "com.example.FooTest", "com.example.FooTest"),
            ),
        )

        parsed.options.testClasses shouldBe listOf("com.example.FooTest")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArgumentCases")
    fun `rejects invalid test run options`(label: String, action: () -> Unit, message: String) {
        assertInvalidArgument(action, message)
    }

    @Test
    fun `parseTestRunOptions filters blank method names`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("", "  ", "method1", "method1"),
                ),
            ),
        )

        parsed.options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
    }

    @Test
    fun `parseTestRunOptions deduplicates method names in map form`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("method1", "method1", "method2"),
                ),
            ),
        )

        parsed.options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1", "method2"))
    }

    @Test
    fun `parseTestRunOptions deduplicates includePatterns after merge`() {
        val parsed = parseTestRunOptions(
            mapOf(
                "includePattern" to "com.example.*",
                "includePatterns" to listOf("com.example.*", "com.other.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        parsed.options.includePatterns shouldBe listOf("com.example.*", "com.other.*")
    }

    @Test
    fun `toBuildRunRequest populates testClasses from testMethods keys for reporting`() {
        val request = TestRunOptions(
            selection = TestRunSelection.Methods(mapOf("com.example.FooTest" to listOf("method1"))),
        ).toBuildRunRequest(
            projectDirectory = testProjectDirectory,
            arguments = emptyList(),
            jvmArguments = emptyList(),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(),
        )

        request.testClasses shouldBe listOf("com.example.FooTest")
        request.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
    }

    @Test
    fun `describeTestOperation formats taskPath with testMethods as class hash methods`() {
        val description = describeTestOperation(
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Methods(
                    methods = mapOf("com.example.FooTest" to listOf("method1", "method2")),
                    taskPath = ":app:test",
                ),
            ),
        )

        description shouldBe "Gradle tests: :app:test methods com.example.FooTest#method1, method2"
    }

    @Test
    fun `describeTestOperation formats testMethods without taskPath as class hash methods`() {
        val description = describeTestOperation(
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Methods(
                    methods = mapOf("com.example.FooTest" to listOf("method1")),
                ),
            ),
        )

        description shouldBe "Gradle tests: com.example.FooTest#method1"
    }

    @Test
    fun `describeTestOperation separates multiple testMethods classes with semicolons`() {
        val description = describeTestOperation(
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Methods(
                    methods = mapOf(
                        "com.example.FooTest" to listOf("method1", "method2"),
                        "com.example.BarTest" to listOf("method3"),
                    ),
                ),
            ),
        )

        description shouldBe "Gradle tests: com.example.FooTest#method1, method2; com.example.BarTest#method3"
    }

    @Test
    fun `fromPersistedFlat reconstructs method selection when testClasses are reporting duplicates`() {
        TestRunSelection.fromPersistedFlat(
            testClasses = listOf("com.example.FooTest"),
            testMethods = mapOf("com.example.FooTest" to listOf("method1")),
            taskPath = ":app:test",
            includePatterns = emptyList(),
        ) shouldBe TestRunSelection.Methods(
            methods = mapOf("com.example.FooTest" to listOf("method1")),
            taskPath = ":app:test",
        )
    }

    companion object {
        @JvmStatic
        fun invalidArgumentCases(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "mixed class and Class.method entries in testClasses",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testClasses" to listOf(
                                    "com.example.FooTest",
                                    "com.example.BarTest.testBar",
                                ),
                            ),
                        )
                    },
                    "testClasses cannot mix fully qualified class names with Class.method entries; use testMethods for method selection",
                ),
                Arguments.of(
                    "blank testMethods map keys",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testMethods" to mapOf("" to listOf("method1")),
                            ),
                        )
                    },
                    "testMethods map keys must be non-blank",
                ),
                Arguments.of(
                    "blank testMethods array class names",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testMethods" to listOf(
                                    mapOf("class" to "", "methods" to listOf("method1")),
                                ),
                            ),
                        )
                    },
                    "testMethods array entries require a non-blank class name",
                ),
                Arguments.of(
                    "testMethods array entries with multiple class keys",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testMethods" to listOf(
                                    mapOf(
                                        "class" to "com.example.FooTest",
                                        "className" to "com.example.BarTest",
                                        "methods" to listOf("method1"),
                                    ),
                                ),
                            ),
                        )
                    },
                    "testMethods array entries must specify exactly one of class, className, or testClass",
                ),
                Arguments.of(
                    "testMethods when all method names are blank",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testMethods" to mapOf(
                                    "com.example.FooTest" to listOf("", "  "),
                                ),
                            ),
                        )
                    },
                    "testMethods entries must contain at least one method name",
                ),
                Arguments.of(
                    "includePatterns when tasks are blank after filtering",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "includePatterns" to listOf("com.example.*"),
                                "tasks" to listOf("", "  "),
                            ),
                        ).options.validate()
                    },
                    "includePattern/includePatterns requires tasks for test task scoping",
                ),
                Arguments.of(
                    "methods field in array form validation errors",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testMethods" to listOf(
                                    mapOf("class" to "com.example.FooTest", "methods" to listOf(1)),
                                ),
                            ),
                        )
                    },
                    "methods values must contain only strings",
                ),
                Arguments.of(
                    "missing selection mechanism",
                    { TestRunOptions().validate() },
                    "At least one of testClasses, testMethods, or includePattern/includePatterns must be provided",
                ),
                Arguments.of(
                    "taskPath without classes or methods",
                    {
                        TestRunOptions(
                            selection = TestRunSelection.Patterns(listOf("com.example.*")),
                            tasks = listOf(":app:test"),
                        ).validate(inputTaskPath = ":app:test")
                    },
                    "taskPath requires non-empty testClasses or testMethods",
                ),
                Arguments.of(
                    "multiple selection mechanisms",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testClasses" to listOf("com.example.FooTest"),
                                "testMethods" to mapOf("com.example.FooTest" to listOf("method1")),
                            ),
                        )
                    },
                    "Specify only one of testClasses, testMethods, or includePattern/includePatterns",
                ),
                Arguments.of(
                    "testClasses combined with includePatterns",
                    {
                        parseTestRunOptions(
                            mapOf(
                                "testClasses" to listOf("com.example.FooTest"),
                                "includePatterns" to listOf("com.example.*"),
                                "tasks" to listOf(":app:test"),
                            ),
                        ).options.validate()
                    },
                    "Specify only one of testClasses, testMethods, or includePattern/includePatterns",
                ),
                Arguments.of(
                    "tasks that omit taskPath when both are specified",
                    {
                        TestRunOptions(
                            selection = TestRunSelection.Classes(
                                classes = listOf("com.example.FooTest"),
                                taskPath = ":app:test",
                            ),
                            tasks = listOf(":other:test"),
                        ).validate()
                    },
                    "tasks must include taskPath when both are specified",
                ),
                Arguments.of(
                    "includePatterns without tasks",
                    { TestRunOptions(selection = TestRunSelection.Patterns(listOf("com.example.*"))).validate() },
                    "includePattern/includePatterns requires tasks for test task scoping",
                ),
            )
    }

    @Test
    fun `validateJvmTestProjectScope rejects unscoped classes in multi-project builds`() {
        val connection = gradleProjectConnection(
            gradleProjectNode(
                children = listOf(gradleProjectNode(name = "app", path = ":app")),
            ),
        )

        val error = shouldThrow<McpException> {
            validateJvmTestProjectScope(
                connection = connection,
                selection = TestRunSelection.Classes(listOf("com.example.FooTest")),
                tasks = emptyList(),
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "taskPath"
    }

    @Test
    fun `validateJvmTestProjectScope allows unscoped classes for single-project builds`() {
        val connection = gradleProjectConnection(gradleProjectNode())

        validateJvmTestProjectScope(
            connection = connection,
            selection = TestRunSelection.Classes(listOf("com.example.FooTest")),
            tasks = emptyList(),
        )
    }
}

private fun gradleProjectConnection(project: GradleProject): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getModel" -> {
                val modelType = args?.get(0) as Class<*>
                if (modelType == GradleProject::class.java) project else null
            }
            else -> defaultProxyReturn(method)
        }
    } as ProjectConnection

private fun gradleProjectNode(
    name: String = "root",
    path: String = ":",
    children: List<GradleProject> = emptyList(),
): GradleProject =
    Proxy.newProxyInstance(
        GradleProject::class.java.classLoader,
        arrayOf(GradleProject::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "getPath" -> path
            "getProjectDirectory" -> File("/root")
            "getDescription" -> null
            "getBuildDirectory" -> null
            "getParent" -> null
            "getChildren" -> domainObjectSet(children)
            "getTasks" -> domainObjectSet(emptyList<Any>())
            "getProjectIdentifier" -> null
            else -> defaultProxyReturn(method)
        }
    } as GradleProject

private fun <T> domainObjectSet(items: List<T>): DomainObjectSet<T> =
    object : AbstractSet<T>(), DomainObjectSet<T> {
        override fun iterator(): MutableIterator<T> = items.toMutableList().iterator()

        override val size: Int get() = items.size

        override fun getAll(): List<T> = items

        override fun getAt(index: Int): T = items[index]
    }
