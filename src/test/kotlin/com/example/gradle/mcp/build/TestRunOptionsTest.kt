package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.assertInvalidArgument
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestRunOptionsTest {
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

    @Test
    fun `parseTestRunOptions rejects blank testMethods map keys`() {
        assertInvalidArgument(
            {
                parseTestRunOptions(
                    mapOf(
                        "testMethods" to mapOf("" to listOf("method1")),
                    ),
                )
            },
            "testMethods map keys must be non-blank",
        )
    }

    @Test
    fun `parseTestRunOptions rejects blank testMethods array class names`() {
        assertInvalidArgument(
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
        )
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
    fun `parseTestRunOptions rejects testMethods array entries with multiple class keys`() {
        assertInvalidArgument(
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
        )
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
    fun `parseTestRunOptions rejects testMethods when all method names are blank`() {
        assertInvalidArgument(
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
        )
    }

    @Test
    fun `validate rejects includePatterns when tasks are blank after filtering`() {
        assertInvalidArgument(
            {
                parseTestRunOptions(
                    mapOf(
                        "includePatterns" to listOf("com.example.*"),
                        "tasks" to listOf("", "  "),
                    ),
                ).validate()
            },
            "includePattern/includePatterns requires tasks for test task scoping",
        )
    }

    @Test
    fun `toBuildRunRequest populates testClasses from testMethods keys for reporting`() {
        val request = TestRunOptions(
            selection = TestRunSelection.Methods(mapOf("com.example.FooTest" to listOf("method1"))),
        ).toBuildRunRequest(
            projectDirectory = testProjectDirectory,
            arguments = emptyList(),
            jvmArguments = emptyList(),
            outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
            progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
        )

        request.testClasses shouldBe listOf("com.example.FooTest")
        request.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
    }

    @Test
    fun `parseTestRunOptions reports methods field in array form validation errors`() {
        assertInvalidArgument(
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
        )
    }

    @Test
    fun `validate rejects missing selection mechanism`() {
        assertInvalidArgument(
            { TestRunOptions().validate() },
            "At least one of testClasses, testMethods, or includePattern/includePatterns must be provided",
        )
    }

    @Test
    fun `validate rejects taskPath without classes or methods`() {
        assertInvalidArgument(
            {
                TestRunOptions(
                    selection = TestRunSelection.Patterns(listOf("com.example.*")),
                    tasks = listOf(":app:test"),
                ).validate(inputTaskPath = ":app:test")
            },
            "taskPath requires non-empty testClasses or testMethods",
        )
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
    fun `validate rejects multiple selection mechanisms`() {
        assertInvalidArgument(
            {
                parseTestRunOptions(
                    mapOf(
                        "testClasses" to listOf("com.example.FooTest"),
                        "testMethods" to mapOf("com.example.FooTest" to listOf("method1")),
                    ),
                )
            },
            "Specify only one of testClasses, testMethods, or includePattern/includePatterns",
        )
    }

    @Test
    fun `validate rejects testClasses combined with includePatterns`() {
        assertInvalidArgument(
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
        )
    }

    @Test
    fun `validate rejects tasks that omit taskPath when both are specified`() {
        assertInvalidArgument(
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
        )
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

    @Test
    fun `validate rejects includePatterns without tasks`() {
        assertInvalidArgument(
            { TestRunOptions(selection = TestRunSelection.Patterns(listOf("com.example.*"))).validate() },
            "includePattern/includePatterns requires tasks for test task scoping",
        )
    }
}
