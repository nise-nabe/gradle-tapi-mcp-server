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

        fun consumeLineTerminator() {
            if (source[i] == '\r' && i + 1 < source.length && source[i + 1] == '\n') i += 1
            i += 1
            line += 1
            lineStart = i
        }

        while (i < source.length) {
            val c = source[i]
            if (isLineTerminator(c)) {
                consumeLineTerminator()
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

        fun consumeLineTerminator() {
            if (source[i] == '\r' && i + 1 < source.length && source[i + 1] == '\n') i += 1
            i += 1
            line += 1
            lineStart = i
        }

        // Kotlin scripts may open with a #! shebang line; it is not source text.
        if (source.startsWith("#!")) {
            while (i < source.length && !isLineTerminator(source[i])) i += 1
        }

        while (i < source.length) {
            val c = source[i]
            if (isLineTerminator(c)) {
                consumeLineTerminator()
                continue
            }
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    i += 2
                    while (i < source.length && !isLineTerminator(source[i])) i += 1
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    // Kotlin block comments nest; stay in comment until depth balances.
                    i += 2
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when {
                            isLineTerminator(source[i]) -> consumeLineTerminator()
                            source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                                depth += 1
                                i += 2
                            }
                            source[i] == '*' && i + 1 < source.length && source[i + 1] == '/' -> {
                                depth -= 1
                                i += 2
                            }
                            else -> i += 1
                        }
                    }
                }
                c == '"' && i + 2 < source.length &&
                    source[i + 1] == '"' && source[i + 2] == '"' -> {
                    // Kotlin raw string / Java text block: no escapes, closes at the next """
                    i += 3
                    while (i < source.length) {
                        when {
                            isLineTerminator(source[i]) -> consumeLineTerminator()
                            i + 2 < source.length &&
                                source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"' -> {
                                i += 3
                                break
                            }
                            else -> i += 1
                        }
                    }
                }
                c == '`' -> {
                    // Kotlin backtick identifier: the span between backticks is one name.
                    i += 1
                    val start = i
                    while (i < source.length && source[i] != '`' && !isLineTerminator(source[i])) i += 1
                    if (i > start) {
                        out.add(
                            IdentifierOccurrence(
                                name = source.substring(start, i),
                                line = line,
                                column = start - lineStart + 1,
                            ),
                        )
                    }
                    if (i < source.length && source[i] == '`') i += 1
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i += 1
                    while (i < source.length) {
                        val ch = source[i]
                        when {
                            ch == '\\' && i + 1 < source.length -> i += 2
                            isLineTerminator(ch) -> consumeLineTerminator()
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

    private fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r'

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentContinue(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
