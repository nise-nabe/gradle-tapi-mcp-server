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
        "Read a UTF-8 snippet from a dependency *-sources.jar (or sourceRoot). " +
            "Use after search hits (gav + path + optional line). " +
            "contextLines default 10 around line; omit line for the whole file."

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
                "gav" to stringProp("group:name:version from a search hit (alt: group+name+version)."),
                "group" to stringProp("Artifact group when gav is omitted."),
                "name" to stringProp("Artifact name when gav is omitted."),
                "version" to stringProp("Artifact version when gav is omitted."),
                "path" to stringProp("Path inside the sources jar / source tree (from search hit)."),
                "line" to integerProp("Optional 1-based anchor line from a search hit."),
                "contextLines" to integerProp("Lines before/after line (default 10). Ignored when line omitted."),
                "sourceRoot" to stringProp("Explicit jar/zip/dir/file when cache lookup is not enough."),
                "gradleUserHome" to stringProp("Gradle cache home for *-sources.jar lookup; else connected."),
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