package com.example.gradle.mcp.dependency

enum class TokenMode {
    ALL,
    IDENTS,
    ;

    fun wireName(): String =
        when (this) {
            ALL -> "all"
            IDENTS -> "idents"
        }

    companion object {
        fun parse(raw: String?): TokenMode {
            if (raw.isNullOrBlank()) {
                return ALL
            }
            return when (raw.trim().lowercase()) {
                "all" -> ALL
                "idents", "ident" -> IDENTS
                else -> throw IllegalArgumentException("Unknown tokenMode `$raw` (expected all|idents)")
            }
        }
    }
}