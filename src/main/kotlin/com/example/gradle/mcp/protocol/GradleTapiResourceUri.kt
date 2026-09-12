package com.example.gradle.mcp.protocol

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * `gradle-tapi://` resource URI for Phase 1 snapshots.
 *
 * Authority is the **URL-encoded** absolute project root so it stays one path
 * segment (RFC 6570 Level 1 `{projectRoot}`). Example:
 * `gradle-tapi://%2Fworkspace/connection/status?refresh=true`.
 *
 * [toUri] / [toToolArgs] always emit [File.getAbsoluteFile] so a relative
 * constructor argument cannot change identity with the process working directory.
 */
internal data class GradleTapiResourceUri(
    val projectDirectory: File,
    val kind: GradleTapiResourceKind,
    val query: Map<String, String> = emptyMap(),
) {
    private val absoluteProjectRoot: File = projectDirectory.absoluteFile

    fun toUri(): String = buildString {
        append(SCHEME)
        append("://")
        append(encodeSegment(absoluteProjectRoot.path))
        append(kind.path)
        val queryString = query.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "${encodeSegment(key)}=${encodeSegment(value)}"
            }
        if (queryString.isNotEmpty()) {
            append('?')
            append(queryString)
        }
    }

    fun toToolArgs(): Map<String, Any> = buildMap {
        query.forEach { (key, raw) ->
            if (key == "projectDirectory" || key == "buildId") {
                return@forEach
            }
            put(key, coerceQueryValue(key, raw))
        }
        put("projectDirectory", absoluteProjectRoot.path)
        kind.buildId?.let { put("buildId", it) }
    }

    companion object {
        const val SCHEME = "gradle-tapi"
        const val JSON_MIME_TYPE = "application/json"
        const val PROJECT_ROOT_VARIABLE = "projectRoot"

        fun parse(uri: String): GradleTapiResourceUri {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) {
                throw invalidUri("Resource URI must not be blank")
            }
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator <= 0 ||
                !trimmed.substring(0, schemeSeparator).equals(SCHEME, ignoreCase = true)
            ) {
                throw invalidUri("Resource URI must use $SCHEME://, got: $uri")
            }
            val rest = trimmed.substring(schemeSeparator + "://".length)
            val withoutFragment = rest.substringBefore('#')
            val authorityAndPath = withoutFragment.substringBefore('?')
            val queryString = withoutFragment.substringAfter('?', missingDelimiterValue = "")
            if (authorityAndPath.isEmpty()) {
                throw invalidUri("Resource URI is missing an encoded project root: $uri")
            }
            val slash = authorityAndPath.indexOf('/')
            if (slash <= 0) {
                throw invalidUri("Resource URI is missing a resource path: $uri")
            }
            val encodedRoot = authorityAndPath.substring(0, slash)
            val path = authorityAndPath.substring(slash)
            val decodedRoot = decodeSegment(encodedRoot, uri)
            val decodedDirectory = File(decodedRoot)
            if (!decodedDirectory.isAbsolute) {
                throw invalidUri(
                    "Resource URI project root must be an absolute path, got: $uri",
                )
            }
            val projectDirectory = decodedDirectory.absoluteFile
            if (projectDirectory.path.isBlank()) {
                throw invalidUri("Resource URI project root is blank: $uri")
            }
            return GradleTapiResourceUri(
                projectDirectory = projectDirectory,
                kind = GradleTapiResourceKind.parsePath(path, uri),
                query = parseQuery(queryString, uri),
            )
        }

        internal fun encodeSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        internal fun decodeSegment(value: String, uri: String): String =
            try {
                // RFC 3986: '+' is a literal plus. URLDecoder is form-encoding and would
                // turn it into a space, disagreeing with the SDK's decodeURLPart matcher.
                URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
            } catch (exception: IllegalArgumentException) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Resource URI contains malformed percent-encoding: $uri",
                    exception,
                )
            }

        private fun parseQuery(queryString: String, uri: String): Map<String, String> {
            if (queryString.isEmpty()) {
                return emptyMap()
            }
            return queryString.split('&').mapNotNull { pair ->
                if (pair.isEmpty()) {
                    return@mapNotNull null
                }
                val eq = pair.indexOf('=')
                val rawKey = if (eq < 0) pair else pair.substring(0, eq)
                val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
                val key = decodeSegment(rawKey, uri)
                if (key.isBlank()) {
                    throw invalidUri("Resource URI has a blank query key: $uri")
                }
                key to decodeSegment(rawValue, uri)
            }.toMap()
        }

        private fun coerceQueryValue(key: String, raw: String): Any =
            when (key) {
                "refresh",
                "includeOutput",
                "includeProgress",
                "includeProblems",
                "includeDownloads",
                "includeTestDetails",
                "tailOutput",
                "waitUntilComplete",
                -> parseBooleanQuery(key, raw)
                "limit",
                "maxDepth",
                "maxChildren",
                "maxOutputChars",
                "sinceStdoutOffset",
                "sinceStderrOffset",
                "waitTimeoutMs",
                "pollIntervalMs",
                -> parseIntQuery(key, raw)
                else -> raw
            }

        private fun parseBooleanQuery(key: String, raw: String): Boolean =
            when (raw.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Query parameter $key must be a boolean (true/false), got: $raw",
                )
            }

        private fun parseIntQuery(key: String, raw: String): Int =
            raw.toIntOrNull() ?: throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Query parameter $key must be an integer, got: $raw",
            )

        private fun invalidUri(message: String): McpException =
            McpException(McpErrorCode.INVALID_ARGUMENT, message)
    }
}

internal sealed class GradleTapiResourceKind {
    abstract val path: String
    open val buildId: String? get() = null

    data object ConnectionStatus : GradleTapiResourceKind() {
        override val path: String = "/connection/status"
    }

    data object Environment : GradleTapiResourceKind() {
        override val path: String = "/environment"
    }

    data object Overview : GradleTapiResourceKind() {
        override val path: String = "/overview"
    }

    data object RecentBuilds : GradleTapiResourceKind() {
        override val path: String = "/builds/recent"
    }

    data class BuildStatus(override val buildId: String) : GradleTapiResourceKind() {
        override val path: String = "/builds/${GradleTapiResourceUri.encodeSegment(buildId)}/status"
    }

    companion object {
        fun parsePath(path: String, uri: String): GradleTapiResourceKind {
            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
            return when {
                segments == listOf("connection", "status") -> ConnectionStatus
                segments == listOf("environment") -> Environment
                segments == listOf("overview") -> Overview
                segments == listOf("builds", "recent") -> RecentBuilds
                segments.size == 3 && segments[0] == "builds" && segments[2] == "status" -> {
                    val buildId = GradleTapiResourceUri.decodeSegment(segments[1], uri)
                    if (buildId.isBlank()) {
                        throw McpException(
                            McpErrorCode.INVALID_ARGUMENT,
                            "Resource URI buildId must not be blank: $uri",
                        )
                    }
                    BuildStatus(buildId)
                }
                else -> throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Unknown gradle-tapi resource path '$path' in $uri",
                )
            }
        }
    }
}
