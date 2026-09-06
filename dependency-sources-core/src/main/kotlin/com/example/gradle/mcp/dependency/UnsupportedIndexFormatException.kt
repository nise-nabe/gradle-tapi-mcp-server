package com.example.gradle.mcp.dependency

class UnsupportedIndexFormatException(
    val foundVersion: Int,
    val expectedVersion: Int,
) : IllegalArgumentException(formatMessage(foundVersion, expectedVersion)) {
    companion object {
        fun formatMessage(foundVersion: Int, expectedVersion: Int): String =
            buildString {
                append("unsupported index format version $foundVersion (expected $expectedVersion)")
                if (foundVersion < expectedVersion) {
                    append("; call gradle_index_dependency_sources to rebuild with format v$expectedVersion")
                }
            }
    }
}
