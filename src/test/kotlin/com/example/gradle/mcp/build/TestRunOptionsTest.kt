package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.assertInvalidArgument
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class TestRunOptionsTest {
    @Test
    fun `parseTestRunOptions normalizes Class method entries in testClasses`() {
        val options = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.FooTest.testBar")),
        )

        options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("testBar")),
        )
        options.selectionNormalized shouldBe true
    }

    @Test
    fun `parseTestRunOptions merges multiple Class method entries into testMethods`() {
        val options = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf(
                    "com.example.FooTest.testOne",
                    "com.example.FooTest.testTwo",
                ),
            ),
        )

        options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("testOne", "testTwo")),
        )
        options.selectionNormalized shouldBe true
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
        val options = parseTestRunOptions(
            mapOf("testClasses" to listOf("com.example.FooTest.test*")),
        )

        options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest.test*"))
        options.selectionNormalized shouldBe false
    }

    @Test
    fun `parseTestRunOptions accepts testClasses`() {
        val options = parseTestRunOptions(mapOf("testClasses" to listOf("com.example.FooTest")))

        options.testClasses shouldBe listOf("com.example.FooTest")
        options.selection shouldBe TestRunSelection.Classes(listOf("com.example.FooTest"))
    }

    @Test
    fun `parseTestRunOptions accepts testMethods map form`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("method1", "method2"),
                ),
            ),
        )

        options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1", "method2"))
        options.selection shouldBe TestRunSelection.Methods(
            mapOf("com.example.FooTest" to listOf("method1", "method2")),
        )
    }

    @Test
    fun `parseTestRunOptions accepts testMethods array form`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to listOf(
                    mapOf("class" to "com.example.FooTest", "methods" to listOf("method1")),
                    mapOf("class" to "com.example.BarTest", "methods" to listOf("method2")),
                ),
            ),
        )

        options.testMethods shouldBe mapOf(
            "com.example.FooTest" to listOf("method1"),
            "com.example.BarTest" to listOf("method2"),
        )
    }

    @Test
    fun `parseTestRunOptions merges includePattern and includePatterns`() {
        val options = parseTestRunOptions(
            mapOf(
                "includePattern" to "com.example.*",
                "includePatterns" to listOf("com.other.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        options.includePatterns shouldBe listOf("com.example.*", "com.other.*")
        options.tasks shouldBe listOf(":app:test")
    }

    @Test
    fun `parseTestRunOptions ignores blank includePatterns entries`() {
        val options = parseTestRunOptions(
            mapOf(
                "includePatterns" to listOf("", "  ", "com.example.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        options.includePatterns shouldBe listOf("com.example.*")
    }

    @Test
    fun `parseTestRunOptions ignores blank includePatterns when testClasses provided`() {
        val options = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("com.example.FooTest"),
                "includePatterns" to listOf(""),
            ),
        )

        options.testClasses shouldBe listOf("com.example.FooTest")
        options.includePatterns shouldBe emptyList()
    }

    @Test
    fun `parseTestRunOptions ignores blank and duplicate tasks`() {
        val options = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("com.example.FooTest"),
                "tasks" to listOf("", "  ", ":app:test", ":app:test"),
            ),
        )

        options.tasks shouldBe listOf(":app:test")
    }

    @Test
    fun `parseTestRunOptions ignores blank and duplicate testClasses`() {
        val options = parseTestRunOptions(
            mapOf(
                "testClasses" to listOf("", "  ", "com.example.FooTest", "com.example.FooTest"),
            ),
        )

        options.testClasses shouldBe listOf("com.example.FooTest")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArgumentCases")
    fun `rejects invalid test run options`(label: String, action: () -> Unit, message: String) {
        assertInvalidArgument(action, message)
    }

    @Test
    fun `parseTestRunOptions filters blank method names`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("", "  ", "method1", "method1"),
                ),
            ),
        )

        options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
    }

    @Test
    fun `parseTestRunOptions deduplicates method names in map form`() {
        val options = parseTestRunOptions(
            mapOf(
                "testMethods" to mapOf(
                    "com.example.FooTest" to listOf("method1", "method1", "method2"),
                ),
            ),
        )

        options.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1", "method2"))
    }

    @Test
    fun `parseTestRunOptions deduplicates includePatterns after merge`() {
        val options = parseTestRunOptions(
            mapOf(
                "includePattern" to "com.example.*",
                "includePatterns" to listOf("com.example.*", "com.other.*"),
                "tasks" to listOf(":app:test"),
            ),
        )

        options.includePatterns shouldBe listOf("com.example.*", "com.other.*")
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
                        ).validate()
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
                        ).validate()
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
}
