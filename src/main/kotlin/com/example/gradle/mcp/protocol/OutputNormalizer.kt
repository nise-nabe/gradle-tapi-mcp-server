package com.example.gradle.mcp.protocol

object OutputNormalizer {
    fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
