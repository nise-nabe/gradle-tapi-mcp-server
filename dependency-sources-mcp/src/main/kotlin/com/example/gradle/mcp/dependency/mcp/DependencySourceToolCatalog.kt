package com.example.gradle.mcp.dependency.mcp

data class DependencySourceToolSpec(
    val name: String,
    val description: String,
    val schema: Map<String, Any>,
)

object DependencySourceToolCatalog {
    const val INDEX_TOOL: String = "gradle_index_dependency_sources"
    const val SEARCH_TOOL: String = "gradle_search_dependency_sources"
    const val SEARCH_MULTI_TOOL: String = "gradle_search_dependency_sources_multi"
    const val READ_TOOL: String = "gradle_read_dependency_source"

    const val INDEX_DESCRIPTION: String =
        "Index dependency sources (Idea, artifacts[], or sourcePaths[]). " +
            "tokenMode=all (default) includes comments/strings; idents=code only."

    const val SEARCH_DESCRIPTION: String =
        "Exact simple-name locate in dependency sources. Requires prior index for tokenMode."

    const val SEARCH_MULTI_DESCRIPTION: String =
        "Multi-name OR locate; dedup hits and tag matchedQueries. Requires prior index."

    const val READ_DESCRIPTION: String =
        "Read UTF-8 snippet from dependency sources jar/dir. " +
            "Need gav|group+name+version + path. Cache jars by coords; Idea/sourcePaths: prefer hit sourceRoot. " +
            "line+contextLines=10; omit line → maxLines=200."

    fun specs(): List<DependencySourceToolSpec> =
        listOf(
            DependencySourceToolSpec(INDEX_TOOL, INDEX_DESCRIPTION, indexSchema()),
            DependencySourceToolSpec(SEARCH_TOOL, SEARCH_DESCRIPTION, searchSchema()),
            DependencySourceToolSpec(SEARCH_MULTI_TOOL, SEARCH_MULTI_DESCRIPTION, searchMultiSchema()),
            DependencySourceToolSpec(READ_TOOL, READ_DESCRIPTION, readSchema()),
        )

    fun indexSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "tokenMode" to stringProp("all (default) or idents."),
                "artifacts" to arrayOfObjects(
                    description = "GAVs; skips Idea keep-set when set.",
                    itemProperties = mapOf(
                        "group" to stringProp("Group"),
                        "name" to stringProp("Name"),
                        "version" to stringProp("Version"),
                    ),
                    required = listOf("group", "name", "version"),
                ),
                "sourcePaths" to arrayOfObjects(
                    description = "Local trees/jars when sources missing.",
                    itemProperties = mapOf(
                        "path" to stringProp("Directory, jar, or zip"),
                        "group" to stringProp("Group label"),
                        "name" to stringProp("Name label"),
                        "version" to stringProp("Version label"),
                    ),
                    required = listOf("path"),
                ),
                "gradleUserHome" to stringProp("Artifacts[] cache home; else connected."),
                "indexDir" to stringProp("Override dir (<dir>/<tokenMode>/)."),
                "forceReindex" to booleanProp("Rebuild on hit."),
            ),
        )

    fun searchSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "query" to stringProp("Exact simple-name to locate"),
                "tokenMode" to stringProp("Must match an index (all|idents). Prefer all."),
                "limit" to nullableIntegerProp("Max hits; omit/null=unlimited, 0=empty."),
                "indexDir" to stringProp("Override dir (reads <dir>/<tokenMode>/)."),
            ),
            required = listOf("query"),
        )

    fun searchMultiSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "queries" to stringArrayProp(
                    description = "Non-empty simple names (OR).",
                    minItems = 1,
                    itemMinLength = 1,
                ),
                "tokenMode" to stringProp("Must match an index (all|idents). Prefer all."),
                "limit" to nullableIntegerProp("Overall max after merge/sort; omit/null=unlimited, 0=empty."),
                "perQueryLimit" to nullableIntegerProp(
                    "Per-query cap; omit/null=unlimited, 0=empty. Alias: per_query_limit.",
                ),
                "indexDir" to stringProp("Override dir (reads <dir>/<tokenMode>/)."),
            ),
            required = listOf("queries"),
        )

    fun readSchema(): Map<String, Any> =
        objectSchema(
            properties = mapOf(
                "projectDirectory" to stringProp("Project root; omit for default/GRADLE_PROJECT_DIR."),
                "gav" to stringProp("Required unless group+name+version: group:name:version."),
                "group" to stringProp("With name+version when gav omitted."),
                "name" to stringProp("With group+version when gav omitted."),
                "version" to stringProp("With group+name when gav omitted."),
                "path" to stringProp("Path inside sources jar/tree (from search hit)."),
                "line" to integerProp("Optional 1-based anchor; must be within file."),
                "contextLines" to integerProp("Lines before/after line (default 10, max 100)."),
                "maxLines" to integerProp("Whole-file cap when line omitted (default 200, max 2000)."),
                "sourceRoot" to stringProp("Jar/zip/dir/file override; else hit/index/cache."),
                "gradleUserHome" to stringProp("Cache home for *-sources.jar; else connected."),
            ),
            required = listOf("path"),
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

    private fun nullableIntegerProp(description: String): Map<String, Any> =
        mapOf("type" to listOf("integer", "null"), "description" to description)

    private fun stringArrayProp(
        description: String,
        minItems: Int? = null,
        itemMinLength: Int? = null,
    ): Map<String, Any> =
        buildMap {
            put("type", "array")
            put("description", description)
            put(
                "items",
                buildMap {
                    put("type", "string")
                    if (itemMinLength != null) {
                        put("minLength", itemMinLength)
                    }
                },
            )
            if (minItems != null) {
                put("minItems", minItems)
            }
        }

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