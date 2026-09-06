package com.example.gradle.mcp.dependency.mcp

data class DependencySourceToolSpec(
    val name: String,
    val description: String,
    val schema: Map<String, Any>,
)

object DependencySourceToolCatalog {
    const val INDEX_TOOL: String = "gradle_index_dependency_sources"
    const val SEARCH_TOOL: String = "gradle_search_dependency_sources"

    const val INDEX_DESCRIPTION: String =
        "Index dependency sources (Idea, artifacts[], or sourcePaths[]). " +
            "tokenMode=all (default) includes comments/strings; idents=code only."

    const val SEARCH_DESCRIPTION: String =
        "Exact simple-name locate in dependency sources. Requires prior index for tokenMode."

    fun specs(): List<DependencySourceToolSpec> =
        listOf(
            DependencySourceToolSpec(INDEX_TOOL, INDEX_DESCRIPTION, indexSchema()),
            DependencySourceToolSpec(SEARCH_TOOL, SEARCH_DESCRIPTION, searchSchema()),
        )

    fun indexSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "tokenMode" to stringProp("all (default) or idents."),
                "artifacts" to arrayOfObjects(
                    description = "Optional GAVs; skips Idea keep-set when set.",
                    itemProperties = mapOf(
                        "group" to stringProp("Group"),
                        "name" to stringProp("Name"),
                        "version" to stringProp("Version"),
                    ),
                    required = listOf("group", "name", "version"),
                ),
                "sourcePaths" to arrayOfObjects(
                    description = "Local trees/jars when sources jars are missing.",
                    itemProperties = mapOf(
                        "path" to stringProp("Directory, jar, or zip"),
                        "group" to stringProp("Optional group label"),
                        "name" to stringProp("Optional name label"),
                        "version" to stringProp("Optional version label"),
                    ),
                    required = listOf("path"),
                ),
                "indexDir" to stringProp("Index directory override."),
                "forceReindex" to booleanProp("Rebuild on cache hit. Default false."),
            ),
        )

    fun searchSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "query" to stringProp("Exact simple-name to locate"),
                "tokenMode" to stringProp("Must match an index (all|idents). Prefer all."),
                "limit" to integerProp("Max hits (default 100)."),
                "indexDir" to stringProp("Index directory override."),
            ),
            required = listOf("query"),
        )

    private fun objectSchema(
        properties: Map<String, Any>,
        required: List<String> = emptyList(),
    ): Map<String, Any> =
        buildMap {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }

    private fun stringProp(description: String): Map<String, String> =
        mapOf("type" to "string", "description" to description)

    private fun booleanProp(description: String): Map<String, String> =
        mapOf("type" to "boolean", "description" to description)

    private fun integerProp(description: String): Map<String, String> =
        mapOf("type" to "integer", "description" to description)

    private fun arrayOfObjects(
        description: String,
        itemProperties: Map<String, Any>,
        required: List<String>,
    ): Map<String, Any> =
        mapOf(
            "type" to "array",
            "description" to description,
            "items" to objectSchema(properties = itemProperties, required = required),
        )
}