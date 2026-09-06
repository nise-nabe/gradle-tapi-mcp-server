package com.example.gradle.mcp.dependency.mcp

import com.example.gradle.mcp.dependency.DependencyArtifactRef
import com.example.gradle.mcp.dependency.DependencyIndexStore
import com.example.gradle.mcp.dependency.DependencySourceReader
import com.example.gradle.mcp.dependency.IndexRequest
import com.example.gradle.mcp.dependency.ReadSourceRequest
import com.example.gradle.mcp.dependency.SearchMultiRequest
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
        val gradleUserHome = resolveGradleUserHome(
            explicit = args.optionalString("gradleUserHome")?.let(::File),
            projectDirectory = projectDirectory,
            access = access,
            needsArtifactsLookup = artifacts.isNotEmpty(),
        )

        val request = IndexRequest(
            projectDirectory = projectDirectory,
            tokenMode = tokenMode,
            artifacts = artifacts,
            sourcePaths = sourcePaths,
            indexDir = indexDir,
            forceReindex = forceReindex,
            gradleUserHome = gradleUserHome,
        )
        // Hold no-active-build + connection only while resolving the Idea keep-set.
        // Corpus lex / disk write run unlocked so unrelated builds are not blocked.
        val keepSet = if (needsConnection) {
            access.withNoActiveBuild(projectDirectory) {
                access.withConnection(projectDirectory) { connection ->
                    store.resolveKeepSet(request, connection)
                }
            }
        } else {
            store.resolveKeepSet(request, connection = null)
        }
        val result = store.index(request, keepSet)

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
        val limit = args.optionalLimitInt("limit")
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
        return searchResponse(query = query, result = result, includeMatchedQueries = false)
    }

    fun searchMulti(args: Map<String, Any>, access: DependencySourcesGradleAccess): Map<String, Any?> {
        val projectDirectory = access.resolveProjectDirectory(args)
        val queries = args.requiredStringList("queries")
        val tokenMode = args.optionalString("tokenMode")?.let(TokenMode::parse)
        val limit = args.optionalLimitInt("limit")
        val perQueryLimit = args.optionalLimitIntWithAlias("perQueryLimit", "per_query_limit")
        val indexDir = args.optionalString("indexDir")?.let(::File)

        val result = store.searchMulti(
            SearchMultiRequest(
                projectDirectory = projectDirectory,
                queries = queries,
                tokenMode = tokenMode,
                limit = limit,
                perQueryLimit = perQueryLimit,
                indexDir = indexDir,
            ),
        )
        return searchMultiResponse(queries = queries, result = result)
    }

    fun read(args: Map<String, Any>, access: DependencySourcesGradleAccess): Map<String, Any?> {
        val projectDirectory = access.resolveProjectDirectory(args)
        val artifact = parseReadArtifact(args)
        val path = args.requiredString("path")
        val line = args.optionalPositiveInt("line")
        val contextLines = args.optionalNonNegativeInt(
            "contextLines",
            default = ReadSourceRequest.DEFAULT_CONTEXT_LINES,
        )
        val maxLines = args.optionalPositiveInt(
            "maxLines",
        ) ?: ReadSourceRequest.DEFAULT_MAX_LINES
        val sourceRoot = args.optionalString("sourceRoot")?.let(::File)
        val gradleUserHome = resolveGradleUserHome(
            explicit = args.optionalString("gradleUserHome")?.let(::File),
            projectDirectory = projectDirectory,
            access = access,
            needsArtifactsLookup = sourceRoot == null,
        )

        val result = DependencySourceReader.read(
            ReadSourceRequest(
                artifact = artifact,
                path = path,
                line = line,
                contextLines = contextLines,
                maxLines = maxLines,
                sourceRoot = sourceRoot,
                gradleUserHome = gradleUserHome,
            ),
        )
        return linkedMapOf(
            "gav" to result.gav,
            "path" to result.path,
            "sourceRoot" to result.sourceRoot,
            "startLine" to result.startLine,
            "endLine" to result.endLine,
            "lineCount" to result.lineCount,
            "truncated" to result.truncated,
            "snippet" to result.snippet,
        )
    }

    private fun parseReadArtifact(args: Map<String, Any>): DependencyArtifactRef {
        val gav = args.optionalString("gav")
        if (gav != null) {
            if (args.containsKey("group") || args.containsKey("name") || args.containsKey("version")) {
                throw IllegalArgumentException("Provide either gav or group/name/version, not both")
            }
            return DependencySourceReader.parseGav(gav)
        }
        val group = args.optionalString("group")
        val name = args.optionalString("name")
        val version = args.optionalString("version")
        if (group == null || name == null || version == null) {
            throw IllegalArgumentException(
                "Missing artifact coordinates: provide gav or group+name+version",
            )
        }
        return DependencyArtifactRef(group = group, name = name, version = version).also { it.validate() }
    }

    private fun searchResponse(
        query: String,
        result: com.example.gradle.mcp.dependency.SearchResult,
        includeMatchedQueries: Boolean,
    ): Map<String, Any?> {
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
            "hits" to result.hits.map { hit -> hitToMap(hit, includeMatchedQueries) },
        )
    }

    private fun searchMultiResponse(
        queries: List<String>,
        result: com.example.gradle.mcp.dependency.SearchMultiResult,
    ): Map<String, Any?> {
        val stats = result.stats
        return linkedMapOf(
            "queries" to queries,
            "tokenMode" to stats.tokenMode.wireName(),
            "formatVersion" to stats.formatVersion,
            "indexDir" to stats.indexDir.absolutePath,
            "hitCount" to result.hitCount,
            "hitsTruncated" to result.hitsTruncated,
            "docCount" to stats.docCount,
            "nameCount" to stats.nameCount,
            "hits" to result.hits.map { hit -> hitToMap(hit, includeMatchedQueries = true) },
        )
    }

    private fun hitToMap(hit: com.example.gradle.mcp.dependency.LocateHit, includeMatchedQueries: Boolean): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "gav" to hit.gav,
            "path" to hit.path,
            "line" to hit.line,
            "column" to hit.column,
        )
        if (includeMatchedQueries && hit.matchedQueries.isNotEmpty()) {
            map["matchedQueries"] = hit.matchedQueries
        }
        return map
    }

    private fun resolveGradleUserHome(
        explicit: File?,
        projectDirectory: File,
        access: DependencySourcesGradleAccess,
        needsArtifactsLookup: Boolean,
    ): File? {
        if (explicit != null) return explicit
        if (!needsArtifactsLookup) return null
        return access.gradleUserHome(projectDirectory)
    }

    private fun parseArtifacts(raw: Any?): List<DependencyArtifactRef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*>
            ?: throw IllegalArgumentException("artifacts must be an array of objects")
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
        if (raw == null) return emptyList()
        val list = raw as? List<*>
            ?: throw IllegalArgumentException("sourcePaths must be an array of objects")
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

private fun Map<String, Any>.optionalLimitInt(key: String): Int? {
    if (!containsKey(key)) return null
    val parsed = when (val value = this[key]) {
        null -> return null
        is Number -> value.toExactLimitIntOrNull()
        is String -> value.toIntOrNull()
        else -> null
    } ?: throw IllegalArgumentException("Argument must be a non-negative integer: $key")
    if (parsed < 0) {
        throw IllegalArgumentException("Argument must be non-negative: $key")
    }
    return parsed
}

private fun Map<String, Any>.optionalLimitIntWithAlias(primaryKey: String, aliasKey: String): Int? =
    if (containsKey(primaryKey)) {
        optionalLimitInt(primaryKey)
    } else {
        optionalLimitInt(aliasKey)
    }

private fun Map<String, Any>.optionalPositiveInt(key: String): Int? {
    if (!containsKey(key)) return null
    val parsed = optionalExactInt(key) ?: throw IllegalArgumentException("Argument must be an integer: $key")
    if (parsed < 1) {
        throw IllegalArgumentException("Argument must be >= 1: $key")
    }
    return parsed
}

private fun Map<String, Any>.optionalNonNegativeInt(key: String, default: Int): Int {
    if (!containsKey(key)) return default
    val parsed = optionalExactInt(key) ?: throw IllegalArgumentException("Argument must be an integer: $key")
    if (parsed < 0) {
        throw IllegalArgumentException("Argument must be non-negative: $key")
    }
    return parsed
}

private fun Map<String, Any>.optionalExactInt(key: String): Int? {
    return when (val value = this[key]) {
        null -> null
        is Number -> value.toExactLimitIntOrNull()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun Number.toExactLimitIntOrNull(): Int? {
    val longValue = when (this) {
        is Int -> return this
        is Long -> this
        is Short -> toLong()
        is Byte -> toLong()
        else -> {
            val doubleValue = toDouble()
            if (!doubleValue.isFinite() || doubleValue != kotlin.math.truncate(doubleValue)) {
                return null
            }
            doubleValue.toLong()
        }
    }
    return if (longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        longValue.toInt()
    } else {
        null
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.requiredStringList(key: String): List<String> {
    val value = this[key]
        ?: throw IllegalArgumentException("Missing required argument: $key")
    val list = value as? List<*>
        ?: throw IllegalArgumentException("Required argument must be a string array: $key")
    if (list.isEmpty()) {
        throw IllegalArgumentException("Required argument must be a non-empty string array: $key")
    }
    return list.mapIndexed { index, item ->
        val string = item as? String
        if (string == null || string.isBlank()) {
            throw IllegalArgumentException("Required argument must contain only non-blank strings: $key[$index]")
        }
        string
    }
}

private fun Map<*, *>.mapString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<*, *>.requiredMapString(key: String, context: String): String {
    val value = mapString(key)
    if (value != null) return value
    throw IllegalArgumentException("$context.$key must be a non-blank string")
}