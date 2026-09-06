package com.example.gradle.mcp.dependency

data class IdentifierOccurrence(
    val name: String,
    val line: Int,
    val column: Int,
)

object IdentifierLexer {
    fun tokenize(source: String, mode: TokenMode): List<IdentifierOccurrence> =
        when (mode) {
            TokenMode.ALL -> tokenizeAll(source)
            TokenMode.IDENTS -> tokenizeIdents(source)
        }

    private fun tokenizeAll(source: String): List<IdentifierOccurrence> {
        val out = ArrayList<IdentifierOccurrence>()
        var line = 1
        var lineStart = 0
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\n') {
                line += 1
                lineStart = i + 1
                i += 1
                continue
            }
            if (isIdentStart(c)) {
                val start = i
                i += 1
                while (i < source.length && isIdentContinue(source[i])) {
                    i += 1
                }
                out.add(
                    IdentifierOccurrence(
                        name = source.substring(start, i),
                        line = line,
                        column = start - lineStart + 1,
                    ),
                )
                continue
            }
            i += 1
        }
        return out
    }

    private fun tokenizeIdents(source: String): List<IdentifierOccurrence> {
        val out = ArrayList<IdentifierOccurrence>()
        var line = 1
        var lineStart = 0
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\n') {
                line += 1
                lineStart = i + 1
                i += 1
                continue
            }
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    i += 2
                    while (i < source.length && source[i] != '\n') i += 1
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') {
                            line += 1
                            lineStart = i + 1
                        }
                        i += 1
                    }
                    if (i + 1 < source.length) i += 2
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    i += 1
                    while (i < source.length) {
                        val ch = source[i]
                        when {
                            ch == '\\' && i + 1 < source.length -> i += 2
                            ch == '\n' -> {
                                line += 1
                                lineStart = i + 1
                                i += 1
                            }
                            ch == quote -> {
                                i += 1
                                break
                            }
                            else -> i += 1
                        }
                    }
                }
                isIdentStart(c) -> {
                    val start = i
                    i += 1
                    while (i < source.length && isIdentContinue(source[i])) i += 1
                    out.add(
                        IdentifierOccurrence(
                            name = source.substring(start, i),
                            line = line,
                            column = start - lineStart + 1,
                        ),
                    )
                }
                else -> i += 1
            }
        }
        return out
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentContinue(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}