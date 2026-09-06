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
    val limit: Int = NameLocateIndex.DEFAULT_LIMIT,
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

class DependencyIndexStore {
    private val memory = ConcurrentHashMap<String, NameLocateIndex>()

    fun defaultIndexDir(projectDirectory: File, tokenMode: TokenMode): File =
        File(projectDirectory, ".gradle/mcp-dependency-sources/${tokenMode.wireName()}")

    fun resolveIndexDir(projectDirectory: File, tokenMode: TokenMode, override: File?): File =
        // Always isolate by tokenMode, including indexDir overrides, so all/idents never share a tree.
        if (override != null) File(override, tokenMode.wireName())
        else defaultIndexDir(projectDirectory, tokenMode)

    fun index(
        request: IndexRequest,
        connection: ProjectConnection?,
    ): IndexResult {
        val explicit = request.artifacts.isNotEmpty() || request.sourcePaths.isNotEmpty()
        val keepSet = DependencyKeepSetResolver.resolve(
            connection = if (explicit) null else connection,
            artifacts = request.artifacts,
            sourcePaths = request.sourcePaths,
            gradleUserHome = request.gradleUserHome,
        )
        val fingerprint = KeepSetFingerprint.compute(
            tokenMode = request.tokenMode,
            keepSetMode = keepSet.mode,
            members = keepSet.members,
        )
        val indexDir = resolveIndexDir(request.projectDirectory, request.tokenMode, request.indexDir)
        val key = cacheKey(request.projectDirectory, request.tokenMode, indexDir)

        if (!request.forceReindex) {
            val loaded = NameLocateIndex.tryLoad(
                directory = indexDir,
                expectedFingerprint = fingerprint,
                expectedTokenMode = request.tokenMode,
            )
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
        built.writeTo(indexDir)
        memory[key] = built
        return IndexResult(
            stats = built.stats(indexDir, cacheHit = false),
            memberCount = keepSet.members.size,
        )
    }

    fun search(request: SearchRequest): SearchResult {
        require(request.query.isNotBlank()) { "query must not be blank" }
        val index = loadForSearch(request.projectDirectory, request.tokenMode, request.indexDir)
            ?: throw IllegalArgumentException(
                "No dependency-sources index found for this project/tokenMode. " +
                    "Call gradle_index_dependency_sources first.",
            )
        val limit = request.limit.coerceAtLeast(0)
        val hits = index.locate(request.query, limit = if (limit == 0) Int.MAX_VALUE else limit)
        val truncated = limit > 0 && hits.size >= limit
        val indexDir = resolveIndexDir(request.projectDirectory, index.tokenMode, request.indexDir)
        return SearchResult(
            hits = hits,
            stats = index.stats(indexDir, cacheHit = true),
            hitCount = hits.size,
            hitsTruncated = truncated,
        )
    }

    private fun loadForSearch(
        projectDirectory: File,
        tokenMode: TokenMode?,
        indexDirOverride: File?,
    ): NameLocateIndex? {
        val modes = if (tokenMode != null) listOf(tokenMode) else listOf(TokenMode.ALL, TokenMode.IDENTS)
        for (mode in modes) {
            val indexDir = resolveIndexDir(projectDirectory, mode, indexDirOverride)
            val key = cacheKey(projectDirectory, mode, indexDir)
            memory[key]?.let { return it }
            val loaded = NameLocateIndex.tryLoad(
                directory = indexDir,
                expectedFingerprint = null,
                expectedTokenMode = mode,
            ) ?: continue
            memory[key] = loaded
            return loaded
        }
        return null
    }

    private fun cacheKey(projectDirectory: File, tokenMode: TokenMode, indexDir: File): String =
        projectDirectory.canonicalFile.absolutePath + "|" + tokenMode.wireName() + "|" +
            indexDir.canonicalFile.absolutePath
}