package com.example.gradle.mcp.cache

object BuildCachePropertyKeys {
    val CACHE_KEYS = listOf(
        "org.gradle.caching",
        "org.gradle.caching.debug",
        "org.gradle.caching.local.directory",
        "org.gradle.caching.remote.url",
        "org.gradle.caching.remote.allowInsecureProtocol",
        "org.gradle.caching.remote.allowUntrustedServer",
        "org.gradle.configuration-cache",
        "org.gradle.configuration-cache.problems",
        "org.gradle.configuration-cache.max-problems",
        "org.gradle.unsafe.configuration-cache",
        "org.gradle.parallel",
    )

    fun isCacheRelated(key: String): Boolean =
        key in CACHE_KEYS ||
            key.startsWith("org.gradle.caching.") ||
            key.startsWith("org.gradle.configuration-cache.")
}
