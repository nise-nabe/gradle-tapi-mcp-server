package com.example.gradle.mcp.model

data class LimitedText(
    val text: String,
    val truncated: Boolean,
    val totalChars: Int,
)
