package com.example.gradle.mcp.model

import com.example.gradle.mcp.support.defaultProxyReturn
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.build.Help
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.AbstractSet

class ModelSerializersTest {
    private val tasks = listOf(
        TaskSnapshot("help", ":help", "Help message", "help", "task ':help'"),
        TaskSnapshot("build", ":build", "Build project", "build", "task ':build'"),
        TaskSnapshot("test", ":test", "Run tests", "verification", "task ':test'"),
    )

    @Test
    fun `serializeTasks omits tasks by default`() {
        val serialized = ModelSerializers.serializeTasks(tasks, ModelQueryOptions())

        serialized.shouldBeEmpty()
    }

    @Test
    fun `serializeTasks returns slim task fields by default`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true),
        )

        serialized.size shouldBe 3
        serialized[1] shouldBe mapOf("name" to "build", "path" to ":build", "group" to "build")
    }

    @Test
    fun `serializeTasks can include task details`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true, includeTaskDetails = true),
        )

        serialized[1]["description"] shouldBe "Build project"
        serialized[1]["displayName"] shouldBe "task ':build'"
    }

    @Test
    fun `filterTasks supports group prefix and max limits`() {
        val filtered = ModelSerializers.filterTasks(
            tasks,
            ModelQueryOptions(
                includeTasks = true,
                taskGroup = "help",
                taskNamePrefix = "h",
                maxTasks = 1,
            ),
        )

        filtered.map { it.name } shouldBe listOf("help")
    }

    @Test
    fun `output limiter omits text when limit is zero`() {
        val limited = OutputLimiter.limit(
            "BUILD SUCCESSFUL in 1s",
            OutputLimitOptions(maxOutputChars = 0, tailOutput = true),
        )

        limited.text shouldBe ""
        limited.truncated.shouldBeTrue()
        limited.totalChars shouldBe "BUILD SUCCESSFUL in 1s".length
    }

    @Test
    fun `output limiter keeps short text unchanged`() {
        val limited = OutputLimiter.limit("ok", OutputLimitOptions(maxOutputChars = 10, tailOutput = true))

        limited.text shouldBe "ok"
        limited.truncated.shouldBeFalse()
    }

    @Test
    fun `output limiter truncates to tail by default`() {
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val limited = OutputLimiter.limit(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
        )

        limited.truncated.shouldBeTrue()
        limited.totalChars shouldBe text.length
        limited.text.length shouldBeLessThanOrEqual 40
        limited.text shouldStartWith "... [truncated "
    }

    @Test
    fun `output limiter omits prefix when limit is too small`() {
        val limited = OutputLimiter.limit(
            "0123456789abcdef",
            OutputLimitOptions(maxOutputChars = 8, tailOutput = true),
        )

        limited.text shouldBe "89abcdef"
        limited.truncated.shouldBeTrue()
        limited.text.length shouldBe 8
    }

    @Test
    fun `output limiter normalizes CRLF`() {
        val limited = OutputLimiter.limit("a\r\nb", OutputLimitOptions(maxOutputChars = 10))

        limited.text shouldBe "a\nb"
        limited.truncated.shouldBeFalse()
    }

    @Test
    fun `output limiter exposes flat response fields`() {
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val fields = OutputLimiter.limitFields(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
            "stdout",
        )

        (fields["stdout"] as String).length shouldBeLessThanOrEqual 40
        fields["stdoutTruncated"] shouldBe true
        fields["stdoutTotalChars"] shouldBe text.length
    }

    @Test
    fun `model query options parse booleans and limits from args`() {
        val options = ModelQueryOptions.fromArgs(
            mapOf(
                "includeTasks" to true,
                "includeTaskDetails" to true,
                "includeTaskSelectors" to true,
                "taskGroup" to "build",
                "taskNamePrefix" to "co",
                "maxTasks" to 5,
            ),
        )

        options.includeTasks.shouldBeTrue()
        options.includeTaskDetails.shouldBeTrue()
        options.includeTaskSelectors.shouldBeTrue()
        options.taskGroup shouldBe "build"
        options.taskNamePrefix shouldBe "co"
        options.maxTasks shouldBe 5
    }

    @Test
    fun `model query options ignore non-positive maxTasks`() {
        val options = ModelQueryOptions.fromArgs(mapOf("maxTasks" to 0))

        options.maxTasks.shouldBeNull()
    }

    @Test
    fun `project tree limits cap visible children and annotate truncation`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = 2)

        childLimit.visibleChildCount shouldBe 2
        childLimit.truncated.shouldBeTrue()
        childLimit.totalChildCount shouldBe 3
    }

    @Test
    fun `project tree limits omit descendants when max depth reached`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 1, maxDepth = 1, childCount = 1)

        depthLimit.omitChildren.shouldBeTrue()
        depthLimit.truncated.shouldBeTrue()
        depthLimit.totalChildCount shouldBe 1
    }

    @Test
    fun `project tree limits omit all children at root-only depth`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = 0, childCount = 2)

        depthLimit.omitChildren.shouldBeTrue()
        depthLimit.truncated.shouldBeTrue()
        depthLimit.totalChildCount shouldBe 2
    }

    @Test
    fun `project tree limits leave full tree when no caps configured`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = null)
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = null, childCount = 3)

        childLimit.visibleChildCount shouldBe 3
        childLimit.truncated.shouldBeFalse()
        childLimit.totalChildCount.shouldBeNull()
        depthLimit.omitChildren.shouldBeFalse()
    }

    @Test
    fun `basicGradleProjectNode applies maxDepth and maxChildren`() {
        val root = basicGradleProject(
            name = "root",
            path = ":",
            directory = File("/root"),
            children = listOf(
                basicGradleProject(
                    name = "a",
                    path = ":a",
                    directory = File("/root/a"),
                    children = listOf(
                        basicGradleProject("a1", ":a:a1", File("/root/a/a1")),
                    ),
                ),
                basicGradleProject("b", ":b", File("/root/b")),
            ),
        )

        val node = ModelSerializers.basicGradleProjectNode(
            root,
            ProjectTreeOptions(maxDepth = 1, maxChildren = 1),
            depth = 0,
        )

        node["truncated"] shouldBe true
        node["totalChildCount"] shouldBe 2
        val children = node["children"] as List<*>
        children shouldHaveSize 1
        val child = children.first() as Map<*, *>
        child["name"] shouldBe "a"
        child["children"] shouldBe emptyList<Map<String, Any?>>()
        child["truncated"] shouldBe true
        child["totalChildCount"] shouldBe 1
    }

    @Test
    fun `gradleBuild serializes projects and nested included builds`() {
        val included = gradleBuild(
            rootDir = File("/included"),
            rootProject = basicGradleProject("included", ":", File("/included")),
            projects = listOf(basicGradleProject("included", ":", File("/included"))),
        )
        val root = gradleBuild(
            rootDir = File("/root"),
            rootProject = basicGradleProject(
                name = "root",
                path = ":",
                directory = File("/root"),
                children = listOf(basicGradleProject("app", ":app", File("/root/app"))),
            ),
            projects = listOf(
                basicGradleProject("root", ":", File("/root")),
                basicGradleProject("app", ":app", File("/root/app")),
            ),
            includedBuilds = listOf(included),
            editableBuilds = listOf(included),
        )

        val serialized = ModelSerializers.gradleBuild(root)

        serialized["buildRootDir"] shouldBe root.buildIdentifier.rootDir.absolutePath
        serialized["projectCount"] shouldBe 2
        val projects = serialized["projects"] as List<*>
        projects shouldHaveSize 2
        val includedBuilds = serialized["includedBuilds"] as List<*>
        includedBuilds shouldHaveSize 1
        val includedBuild = includedBuilds.first() as Map<*, *>
        includedBuild["buildRootDir"] shouldBe included.buildIdentifier.rootDir.absolutePath
        includedBuild["projectCount"] shouldBe 1
        val editableBuilds = serialized["editableBuilds"] as List<*>
        editableBuilds shouldHaveSize 1
        (editableBuilds.first() as Map<*, *>)["cycleReference"] shouldBe true
    }

    @Test
    fun `help serializer returns rendered text with truncation metadata`() {
        val text = "USAGE: gradle [option...] [task...]\n" + "x".repeat(10_000)
        val help = mockHelp(text)

        val result = ModelSerializers.help(help, HelpLimitOptions(maxChars = 200, tailOutput = true))

        (result["renderedText"] as String).length shouldBeLessThanOrEqual 200
        result["renderedTextTruncated"] shouldBe true
        result["renderedTextTotalChars"] shouldBe text.length
    }

    @Test
    fun `help serializer keeps short help text unchanged`() {
        val text = "USAGE: gradle [option...] [task...]"
        val help = mockHelp(text)

        val result = ModelSerializers.help(help)

        result["renderedText"] shouldBe text
        result["renderedTextTruncated"] shouldBe false
        result["renderedTextTotalChars"] shouldBe text.length
    }

    @Test
    fun `help limit options parse maxChars and tailOutput from args`() {
        val options = HelpLimitOptions.fromArgs(
            mapOf(
                "maxChars" to 4_000,
                "tailOutput" to false,
            ),
        )

        options.maxChars shouldBe 4_000
        options.tailOutput.shouldBeFalse()
    }

    private fun mockHelp(renderedText: String): Help =
        Proxy.newProxyInstance(
            Help::class.java.classLoader,
            arrayOf(Help::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRenderedText" -> renderedText
                else -> defaultProxyReturn(method)
            }
        } as Help

    private fun basicGradleProject(
        name: String,
        path: String,
        directory: File,
        buildTreePath: String? = null,
        children: List<BasicGradleProject> = emptyList(),
    ): BasicGradleProject =
        Proxy.newProxyInstance(
            BasicGradleProject::class.java.classLoader,
            arrayOf(BasicGradleProject::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "getPath" -> path
                "getProjectDirectory" -> directory
                "getBuildTreePath" -> buildTreePath
                "getParent" -> null
                "getChildren" -> domainObjectSet(children)
                "getProjectIdentifier" -> null
                else -> defaultProxyReturn(method)
            }
        } as BasicGradleProject

    private fun gradleBuild(
        rootDir: File,
        rootProject: BasicGradleProject,
        projects: List<BasicGradleProject>,
        includedBuilds: List<GradleBuild> = emptyList(),
        editableBuilds: List<GradleBuild> = emptyList(),
    ): GradleBuild =
        Proxy.newProxyInstance(
            GradleBuild::class.java.classLoader,
            arrayOf(GradleBuild::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBuildIdentifier" -> buildIdentifier(rootDir)
                "getRootProject" -> rootProject
                "getProjects" -> domainObjectSet(projects)
                "getIncludedBuilds" -> domainObjectSet(includedBuilds)
                "getEditableBuilds" -> domainObjectSet(editableBuilds)
                else -> defaultProxyReturn(method)
            }
        } as GradleBuild

    private fun buildIdentifier(rootDir: File): BuildIdentifier =
        Proxy.newProxyInstance(
            BuildIdentifier::class.java.classLoader,
            arrayOf(BuildIdentifier::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRootDir" -> rootDir
                else -> defaultProxyReturn(method)
            }
        } as BuildIdentifier

    @Suppress("UNCHECKED_CAST")
    private fun <T> domainObjectSet(items: List<T>): DomainObjectSet<T> =
        object : AbstractSet<T>(), DomainObjectSet<T> {
            override fun iterator(): MutableIterator<T> = items.toMutableList().iterator()

            override val size: Int get() = items.size

            override fun getAll(): List<T> = items

            override fun getAt(index: Int): T = items[index]
        }
}
