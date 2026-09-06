package com.example.gradle.mcp.dependency.mcp

import com.example.gradle.mcp.dependency.DependencyArtifactRef
import com.example.gradle.mcp.dependency.DependencyIndexStore
import com.example.gradle.mcp.dependency.IndexRequest
import com.example.gradle.mcp.dependency.NameLocateIndex
import com.example.gradle.mcp.dependency.SearchRequest
import com.example.gradle.mcp.dependency.SourcePathRef
import com.example.gradle.mcp.dependency.TokenMode
import java.io.File

class DependencySourcesFacade(
    private val store: DependencyIndexStore = DependencyIndexStore(),
) {
    fun index(args: Map<String, Any>, access: DependencySourcesGradleAccess): Map<String, Any?> {
        val projectDirectory = access.resolveProjectDirectory(args)
        val tokenMode = TokenMode.parse(args.optionalString("tokenMode"))
        val artifacts = parseArtifacts(args["artifacts"])
        val sourcePaths = parseSourcePaths(args["sourcePaths"])
        val indexDir = args.optionalString("indexDir")?.let(::File)
        val forceReindex = args.optionalBoolean("forceReindex", default = false)
        val needsConnection = artifacts.isEmpty() && sourcePaths.isEmpty()

        val result = access.withNoActiveBuild(projectDirectory) {
            val request = IndexRequest(
                projectDirectory = projectDirectory,
                tokenMode = tokenMode,
                artifacts = artifacts,
                sourcePaths = sourcePaths,
                indexDir = indexDir,
                forceReindex = forceReindex,
            )
            if (needsConnection) {
                access.withConnection(projectDirectory) { connection ->
                    store.index(request, connection)
                }
            } else {
                store.index(request, connection = null)
            }
        }

        val stats = result.stats
        return linkedMapOf(
            "cacheHit" to stats.cacheHit,
            "formatVersion" to stats.formatVersion,
            "tokenMode" to stats.tokenMode.wireName(),
            "keepSetMode" to stats.keepSetMode,
            "indexDir" to stats.indexDir.absolutePath,
            "fingerprint" to stats.fingerprint,
            "docCount" to stats.docCount,
            "nameCount" to stats.nameCount,
            "occurrenceCount" to stats.occurrenceCount,
            "memberCount" to result.memberCount,
        )
    }

    fun search(args: Map<String, Any>, access: DependencySourcesGradleAccess): Map<String, Any?> {
        val projectDirectory = access.resolveProjectDirectory(args)
        val query = args.requiredString("query")
        val tokenMode = args.optionalString("tokenMode")?.let(TokenMode::parse)
        val limit = args.optionalPositiveInt("limit") ?: NameLocateIndex.DEFAULT_LIMIT
        val indexDir = args.optionalString("indexDir")?.let(::File)

        val result = store.search(
            SearchRequest(
                projectDirectory = projectDirectory,
                query = query,
                tokenMode = tokenMode,
                limit = limit,
                indexDir = indexDir,
            ),
        )
        val stats = result.stats
        return linkedMapOf(
            "query" to query,
            "tokenMode" to stats.tokenMode.wireName(),
            "formatVersion" to stats.formatVersion,
            "indexDir" to stats.indexDir.absolutePath,
            "hitCount" to result.hitCount,
            "hitsTruncated" to result.hitsTruncated,
            "docCount" to stats.docCount,
            "nameCount" to stats.nameCount,
            "hits" to result.hits.map { hit ->
                linkedMapOf(
                    "gav" to hit.gav,
                    "path" to hit.path,
                    "line" to hit.line,
                    "column" to hit.column,
                )
            },
        )
    }

    private fun parseArtifacts(raw: Any?): List<DependencyArtifactRef> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapIndexed { index, item ->
            val map = item as? Map<*, *>
                ?: throw IllegalArgumentException("artifacts[$index] must be an object")
            DependencyArtifactRef(
                group = map.requiredMapString("group", "artifacts[$index]"),
                name = map.requiredMapString("name", "artifacts[$index]"),
                version = map.requiredMapString("version", "artifacts[$index]"),
            )
        }
    }

    private fun parseSourcePaths(raw: Any?): List<SourcePathRef> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapIndexed { index, item ->
            val map = item as? Map<*, *>
                ?: throw IllegalArgumentException("sourcePaths[$index] must be an object")
            SourcePathRef(
                path = File(map.requiredMapString("path", "sourcePaths[$index]")),
                group = map.mapString("group"),
                name = map.mapString("name"),
                version = map.mapString("version"),
            )
        }
    }
}

private fun Map<String, Any>.requiredString(key: String): String {
    val value = this[key]
    if (value is String && value.isNotBlank()) return value
    throw IllegalArgumentException(
        when (value) {
            null -> "Missing required argument: $key"
            is String -> "Required argument must not be blank: $key"
            else -> "Required argument must be a string: $key"
        },
    )
}

private fun Map<String, Any>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        else -> default
    }

private fun Map<String, Any>.optionalPositiveInt(key: String): Int? =
    when (val value = this[key]) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

private fun Map<*, *>.mapString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<*, *>.requiredMapString(key: String, context: String): String {
    val value = mapString(key)
    if (value != null) return value
    throw IllegalArgumentException("$context.$key must be a non-blank string")
}