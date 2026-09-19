package com.example.gradle.mcp.cache

import com.example.gradle.mcp.protocol.OutputNormalizer

object GradlePropertiesParser {
    fun parse(text: String): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        for (line in OutputNormalizer.normalizeNewlines(text).lines()) {
            parsePropertyLine(line)?.let { (key, value) ->
                properties[key] = value
            }
        }
        return properties
    }

    fun parsePropertyLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null
        }
        val equalsIndex = trimmed.indexOf('=')
        val colonIndex = trimmed.indexOf(':')
        val separator = when {
            equalsIndex > 0 && (colonIndex <= 0 || equalsIndex < colonIndex) -> equalsIndex
            colonIndex > 0 -> colonIndex
            else -> return null
        }
        val key = trimmed.substring(0, separator).trim()
        val value = trimmed.substring(separator + 1).trim()
        if (key.isEmpty()) {
            return null
        }
        return key to value
    }

    fun filterCacheRelated(properties: Map<String, String>): Map<String, String> =
        properties.filterKeys(BuildCachePropertyKeys::isCacheRelated)
}
