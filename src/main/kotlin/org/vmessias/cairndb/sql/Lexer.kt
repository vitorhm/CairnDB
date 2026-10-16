package org.vmessias.cairndb.sql

internal enum class TokenKind { IDENTIFIER, INTEGER, REAL, STRING, BLOB, PARAM, SYMBOL, END }

/** [text] is the unescaped content for strings and quoted identifiers, the hex digits for blobs. */
internal class Token(val kind: TokenKind, val text: String, val start: Int, val end: Int, val quoted: Boolean = false)

internal class Lexer(private val sql: String) {
    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (true) {
            i = skipBlanks(i)
            if (i >= sql.length) {
                tokens += Token(TokenKind.END, "", i, i)
                return tokens
            }
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            val token = when {
                (c == 'x' || c == 'X') && next == '\'' -> blob(i)
                c.isLetter() || c == '_' -> identifier(i)
                c.isDigit() || (c == '.' && next?.isDigit() == true) -> number(i)
                c == '\'' -> string(i)
                c == '"' -> quotedIdentifier(i)
                c == '?' -> Token(TokenKind.PARAM, "?", i, i + 1)
                else -> symbol(i)
            }
            tokens += token
            i = token.end
        }
    }

    private fun skipBlanks(from: Int): Int {
        var i = from
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                sql.startsWith("--", i) -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                sql.startsWith("/*", i) -> {
                    val close = sql.indexOf("*/", i + 2)
                    if (close < 0) throw SqlException("Unterminated comment at position $i", i)
                    i = close + 2
                }
                else -> return i
            }
        }
        return i
    }

    private fun identifier(start: Int): Token {
        var i = start
        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
        return Token(TokenKind.IDENTIFIER, sql.substring(start, i), start, i)
    }

    private fun number(start: Int): Token {
        var i = start
        var real = false
        while (i < sql.length && sql[i].isDigit()) i++
        if (i < sql.length && sql[i] == '.') {
            real = true
            i++
            while (i < sql.length && sql[i].isDigit()) i++
        }
        if (i < sql.length && (sql[i] == 'e' || sql[i] == 'E')) {
            var j = i + 1
            if (j < sql.length && (sql[j] == '+' || sql[j] == '-')) j++
            if (j < sql.length && sql[j].isDigit()) {
                while (j < sql.length && sql[j].isDigit()) j++
                real = true
                i = j
            }
        }
        if (i < sql.length && (sql[i].isLetter() || sql[i] == '_')) {
            throw SqlException("Malformed number at position $start", start)
        }
        return Token(if (real) TokenKind.REAL else TokenKind.INTEGER, sql.substring(start, i), start, i)
    }

    /** Reads a quote-delimited run starting at [start]; a doubled quote stands for the quote itself. */
    private fun quoted(start: Int, quote: Char, what: String): Pair<String, Int> {
        val out = StringBuilder()
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
                if (sql.getOrNull(i + 1) == quote) {
                    out.append(quote)
                    i += 2
                    continue
                }
                return out.toString() to i + 1
            }
            out.append(sql[i++])
        }
        throw SqlException("Unterminated $what starting at position $start", start)
    }

    private fun string(start: Int): Token {
        val (text, end) = quoted(start, '\'', "string")
        return Token(TokenKind.STRING, text, start, end)
    }

    private fun quotedIdentifier(start: Int): Token {
        val (text, end) = quoted(start, '"', "quoted identifier")
        if (text.isEmpty()) throw SqlException("Empty identifier at position $start", start)
        return Token(TokenKind.IDENTIFIER, text, start, end, quoted = true)
    }

    private fun blob(start: Int): Token {
        val (hex, end) = quoted(start + 1, '\'', "blob literal")
        if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw SqlException("Malformed blob literal at position $start", start)
        }
        return Token(TokenKind.BLOB, hex, start, end)
    }

    private fun symbol(start: Int): Token {
        val two = sql.substring(start, minOf(start + 2, sql.length))
        if (two in TWO_CHAR_SYMBOLS) return Token(TokenKind.SYMBOL, two, start, start + 2)
        val one = sql[start]
        if (one in ONE_CHAR_SYMBOLS) return Token(TokenKind.SYMBOL, one.toString(), start, start + 1)
        throw SqlException("Unexpected character '$one' at position $start", start)
    }

    private companion object {
        val TWO_CHAR_SYMBOLS = setOf("<=", ">=", "<>", "!=", "==", "||")
        const val ONE_CHAR_SYMBOLS = "+-*/%=<>(),;."
    }
}
