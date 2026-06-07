package com.example.gradle.mcp.cache

internal object BuildCacheUrlRedactor {
    private const val REMOTE_URL_KEY = "org.gradle.caching.remote.url"

    fun sanitizeCacheProperties(properties: Map<String, String>): Map<String, String> =
        properties.mapValues { (key, value) ->
            if (key == REMOTE_URL_KEY) {
                redactUserInfo(value)
            } else {
                value
            }
        }

    fun redactUserInfo(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !trimmed.contains('@')) {
            return trimmed
        }
        return try {
            val uri = java.net.URI(trimmed)
            if (uri.userInfo.isNullOrEmpty()) {
                trimmed
            } else {
                java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            }
        } catch (_: Exception) {
            trimmed.replace(Regex("""^(https?://)[^/@]+@"""), "$1")
        }
    }
}
