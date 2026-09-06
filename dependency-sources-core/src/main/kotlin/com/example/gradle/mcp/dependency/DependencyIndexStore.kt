package com.example.gradle.mcp.dependency

import org.gradle.tooling.ProjectConnection
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class IndexRequest(
    val projectDirectory: File,
    val tokenMode: TokenMode = TokenMode.ALL,
    val artifacts: List<DependencyArtifactRef> = emptyList(),
    val sourcePaths: List<SourcePathRef> = emptyList(),
    val indexDir: File? = null,
    val forceReindex: Boolean = false,
    val gradleUserHome: File? = null,
)

data class SearchRequest(
    val projectDirectory: File,
    val query: String,
    val tokenMode: TokenMode? = null,
    val limit: Int? = null,
    val indexDir: File? = null,
)

data class SearchMultiRequest(
    val projectDirectory: File,
    val queries: List<String>,
    val tokenMode: TokenMode? = null,
    val limit: Int? = null,
    val perQueryLimit: Int? = null,
    val indexDir: File? = null,
)

data class IndexResult(
    val stats: IndexStats,
    val memberCount: Int,
)

data class SearchResult(
    val hits: List<LocateHit>,
    val stats: IndexStats,
    val hitCount: Int,
    val hitsTruncated: Boolean,
)

data class SearchMultiResult(
    val hits: List<LocateHit>,
    val stats: IndexStats,
    val hitCount: Int,
    val hitsTruncated: Boolean,
)

class DependencyIndexStore {
    private val memory = ConcurrentHashMap<String, NameLocateIndex>()

    fun defaultIndexDir(projectDirectory: File, tokenMode: TokenMode): File =
        File(projectDirectory, ".gradle/mcp-dependency-sources/${tokenMode.wireName()}")

    fun resolveIndexDir(projectDirectory: File, tokenMode: TokenMode, override: File?): File =
        if (override != null) {
            File(override, tokenMode.wireName())
        } else {
            defaultIndexDir(projectDirectory, tokenMode)
        }

    fun resolveKeepSet(
        request: IndexRequest,
        connection: ProjectConnection?,
    ): ResolvedKeepSet {
        val explicit = request.artifacts.isNotEmpty() || request.sourcePaths.isNotEmpty()
        return DependencyKeepSetResolver.resolve(
            connection = if (explicit) null else connection,
            artifacts = request.artifacts,
            sourcePaths = request.sourcePaths,
            gradleUserHome = request.gradleUserHome,
        )
    }

    fun index(
        request: IndexRequest,
        keepSet: ResolvedKeepSet,
    ): IndexResult {
        val fingerprint = KeepSetFingerprint.compute(
            tokenMode = request.tokenMode,
            keepSetMode = keepSet.mode,
            members = keepSet.members,
        )
        val indexDir = resolveIndexDir(request.projectDirectory, request.tokenMode, request.indexDir)
        val key = cacheKey(request.projectDirectory, request.tokenMode, indexDir)

        if (request.forceReindex) {
            memory.remove(key)
        } else {
            val loaded =
                try {
                    NameLocateIndex.tryLoad(
                        directory = indexDir,
                        expectedFingerprint = fingerprint,
                        expectedTokenMode = request.tokenMode,
                    )
                } catch (_: UnsupportedIndexFormatException) {
                    null
                }
            if (loaded != null) {
                memory[key] = loaded
                return IndexResult(
                    stats = loaded.stats(indexDir, cacheHit = true),
                    memberCount = keepSet.members.size,
                )
            }
        }

        val built = NameLocateIndex.build(
            members = keepSet.members,
            tokenMode = request.tokenMode,
            fingerprint = fingerprint,
            keepSetMode = keepSet.mode,
        )
        // Drop any mmap-backed entry before replacing on-disk files (mapped buffers can
        // block directory moves/deletes, especially on Windows).
        memory.remove(key)
        built.writeTo(indexDir)
        memory[key] = built
        return IndexResult(
            stats = built.stats(indexDir, cacheHit = false),
            memberCount = keepSet.members.size,
        )
    }

    fun index(
        request: IndexRequest,
        connection: ProjectConnection?,
    ): IndexResult = index(request, resolveKeepSet(request, connection))

    fun search(request: SearchRequest): SearchResult {
        require(request.query.isNotBlank()) { "query must not be blank" }
        require(request.limit == null || request.limit >= 0) { "limit must be non-negative" }
        val index = loadForSearch(request.projectDirectory, request.tokenMode, request.indexDir)
            ?: throw IllegalArgumentException(
                "No dependency-sources index found for this project/tokenMode. " +
                    "Call gradle_index_dependency_sources first.",
            )
        val indexDir = resolveIndexDir(request.projectDirectory, index.tokenMode, request.indexDir)
        return when (val limit = request.limit) {
            null -> {
                val hits = index.locate(request.query, limit = null)
                SearchResult(
                    hits = hits,
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = hits.size,
                    hitsTruncated = false,
                )
            }
            0 -> {
                index.locate(request.query, limit = 0)
                SearchResult(
                    hits = emptyList(),
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = 0,
                    hitsTruncated = index.postingCount(request.query) > 0,
                )
            }
            else -> {
                // Probe limit+1 so hitsTruncated is false when there are exactly `limit` matches.
                // Only Int.MAX_VALUE skips +1 (signed overflow).
                val probed =
                    if (limit == Int.MAX_VALUE) {
                        index.locate(request.query, limit = Int.MAX_VALUE)
                    } else {
                        index.locate(request.query, limit = limit + 1)
                    }
                val truncated = limit < Int.MAX_VALUE && probed.size > limit
                val hits = if (truncated) probed.take(limit) else probed
                SearchResult(
                    hits = hits,
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = hits.size,
                    hitsTruncated = truncated,
                )
            }
        }
    }

    fun searchMulti(request: SearchMultiRequest): SearchMultiResult {
        require(request.queries.isNotEmpty()) { "queries must not be empty" }
        require(request.queries.all { it.isNotBlank() }) { "queries must not contain blank entries" }
        require(request.limit == null || request.limit >= 0) { "limit must be non-negative" }
        require(request.perQueryLimit == null || request.perQueryLimit >= 0) {
            "perQueryLimit must be non-negative"
        }
        val index = loadForSearch(request.projectDirectory, request.tokenMode, request.indexDir)
            ?: throw IllegalArgumentException(
                "No dependency-sources index found for this project/tokenMode. " +
                    "Call gradle_index_dependency_sources first.",
            )
        val indexDir = resolveIndexDir(request.projectDirectory, index.tokenMode, request.indexDir)
        return when (val limit = request.limit) {
            null -> {
                val hits = index.searchMulti(request.queries, limit = null, perQueryLimit = request.perQueryLimit)
                SearchMultiResult(
                    hits = hits,
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = hits.size,
                    hitsTruncated = perQueryHitsTruncated(index, request.queries, request.perQueryLimit),
                )
            }
            0 -> {
                for (query in request.queries.distinct()) {
                    index.locate(query, limit = 0)
                }
                SearchMultiResult(
                    hits = emptyList(),
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = 0,
                    hitsTruncated = request.queries.any { index.postingCount(it) > 0 } ||
                        perQueryHitsTruncated(index, request.queries, request.perQueryLimit),
                )
            }
            else -> {
                val probed =
                    if (limit == Int.MAX_VALUE) {
                        index.searchMulti(
                            request.queries,
                            limit = Int.MAX_VALUE,
                            perQueryLimit = request.perQueryLimit,
                        )
                    } else {
                        index.searchMulti(
                            request.queries,
                            limit = limit + 1,
                            perQueryLimit = request.perQueryLimit,
                        )
                    }
                val truncated = limit < Int.MAX_VALUE && probed.size > limit
                val hits = if (truncated) probed.take(limit) else probed
                SearchMultiResult(
                    hits = hits,
                    stats = index.stats(indexDir, cacheHit = true),
                    hitCount = hits.size,
                    hitsTruncated = truncated ||
                        perQueryHitsTruncated(index, request.queries, request.perQueryLimit),
                )
            }
        }
    }

    private fun perQueryHitsTruncated(
        index: NameLocateIndex,
        queries: List<String>,
        perQueryLimit: Int?,
    ): Boolean =
        when (perQueryLimit) {
            null -> false
            0 -> queries.any { index.postingCount(it) > 0 }
            Int.MAX_VALUE -> false
            else -> queries.any { index.postingCount(it) > perQueryLimit }
        }

    private fun loadForSearch(
        projectDirectory: File,
        tokenMode: TokenMode?,
        indexDirOverride: File?,
    ): NameLocateIndex? {
        val modes = if (tokenMode != null) listOf(tokenMode) else listOf(TokenMode.ALL, TokenMode.IDENTS)
        var staleFormatError: UnsupportedIndexFormatException? = null
        for (mode in modes) {
            val indexDir = resolveIndexDir(projectDirectory, mode, indexDirOverride)
            val key = cacheKey(projectDirectory, mode, indexDir)
            memory[key]?.let { return it }
            val loaded =
                try {
                    NameLocateIndex.tryLoad(
                        directory = indexDir,
                        expectedFingerprint = null,
                        expectedTokenMode = mode,
                    )
                } catch (error: UnsupportedIndexFormatException) {
                    // Explicit mode: fail fast. Unspecified mode: try the next candidate
                    // (e.g. stale v2 ALL must not hide a valid v3 IDENTS index).
                    if (tokenMode != null) throw error
                    staleFormatError = error
                    continue
                } ?: continue
            memory[key] = loaded
            return loaded
        }
        if (staleFormatError != null) throw staleFormatError
        return null
    }

    private fun cacheKey(projectDirectory: File, tokenMode: TokenMode, indexDir: File): String =
        projectDirectory.canonicalFile.absolutePath + "|" + tokenMode.wireName() + "|" +
            indexDir.canonicalFile.absolutePath
}
