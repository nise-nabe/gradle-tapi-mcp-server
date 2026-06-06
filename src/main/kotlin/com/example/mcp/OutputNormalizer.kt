package com.example.mcp

object OutputNormalizer {
    fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
