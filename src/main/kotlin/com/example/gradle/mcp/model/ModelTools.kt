package com.example.gradle.mcp.model

import com.example.gradle.mcp.ConnectionScope
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.projectDirectoryProperty
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.gradle.ProjectPublications

internal fun projectTreeProperties(): Map<String, Any> =
    mapOf(
        "maxDepth" to integerProperty("Maximum project tree depth (root=0); deeper child projects are omitted"),
        "maxChildren" to integerProperty("Maximum child projects per node (omit for unlimited)"),
        "projectDirectory" to projectDirectoryProperty(
            "Gradle project root to query. Defaults to GRADLE_PROJECT_DIR when set.",
        ),
    )

internal fun projectTreeSchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties())

internal fun modelQueryProperties(): Map<String, Any> =
    mapOf(
        "includeTasks" to booleanProperty("Include task lists. Default false to save tokens."),
        "includeTaskDetails" to booleanProperty("Include task description and displayName. Default false."),
        "taskGroup" to stringProperty("Filter tasks by Gradle task group"),
        "taskNamePrefix" to stringProperty("Filter tasks whose name starts with this prefix"),
        "maxTasks" to integerProperty("Maximum number of tasks to return after filtering"),
    )

internal fun modelQuerySchema(): Map<String, Any> =
    objectSchema(properties = projectTreeProperties() + modelQueryProperties())

internal fun invocationsQuerySchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to projectDirectoryProperty(
                "Gradle project root to query. Defaults to GRADLE_PROJECT_DIR when set.",
            ),
        ) + modelQueryProperties() + mapOf(
            "includeTaskSelectors" to booleanProperty("Include task selectors. Default false to save tokens."),
        ),
    )

internal fun publicationsSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to projectDirectoryProperty(
                "Gradle project root to query. Defaults to GRADLE_PROJECT_DIR when set.",
            ),
        ),
    )

context(runtime: ConnectionScope)
fun modelTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_get_project_overview",
            description = "Fetch project hierarchy and task counts without task lists. Token-efficient default for project context ingestion.",
            schema = projectTreeSchema(),
        ) { args ->
            val treeOptions = ProjectTreeOptions.fromArgs(args)
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val project = connection.getModel(GradleProject::class.java)
                jsonResult(ModelSerializers.projectOverview(project, treeOptions))
            }
        },
        tool(
            name = "gradle_get_gradle_build",
            description = "Fetch GradleBuild structure: root project tree, all projects, included builds, and editable builds. Lightweight and read-only; no tasks. Prefer for composite or includeBuild repositories.",
            schema = projectTreeSchema(),
        ) { args ->
            val treeOptions = ProjectTreeOptions.fromArgs(args)
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val build = connection.getModel(GradleBuild::class.java)
                jsonResult(ModelSerializers.gradleBuild(build, treeOptions))
            }
        },
        tool(
            name = "gradle_get_project_model",
            description = "Fetch the GradleProject model. Tasks are omitted by default; set includeTasks=true only when needed.",
            schema = modelQuerySchema(),
        ) { args ->
            val options = ModelQueryOptions.fromArgs(args)
            val treeOptions = ProjectTreeOptions.fromArgs(args)
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val project = connection.getModel(GradleProject::class.java)
                jsonResult(ModelSerializers.gradleProject(project, options, treeOptions))
            }
        },
        tool(
            name = "gradle_get_build_invocations",
            description = "Fetch runnable Gradle tasks. Task selectors are omitted by default; tasks return name/path/group unless includeTaskDetails=true.",
            schema = invocationsQuerySchema(),
        ) { args ->
            val options = ModelQueryOptions.fromArgs(args).copy(includeTasks = true)
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val invocations = connection.getModel(BuildInvocations::class.java)
                jsonResult(ModelSerializers.buildInvocations(invocations, options))
            }
        },
        tool(
            name = "gradle_get_project_publications",
            description = "Fetch publications declared by the build.",
            schema = publicationsSchema(),
        ) { args ->
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val publications = connection.getModel(ProjectPublications::class.java)
                jsonResult(ModelSerializers.projectPublications(publications))
            }
        },
    )
