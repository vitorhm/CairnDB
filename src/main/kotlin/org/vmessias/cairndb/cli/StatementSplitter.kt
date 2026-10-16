package org.vmessias.cairndb.cli

/** One SQL statement cut out of an input, without its terminating semicolon. [line] is where it starts. */
internal class Statement(val text: String, val line: Int)

/**
 * Cuts a stream of text into statements at the semicolons that are not inside a string, a quoted
 * identifier or a comment. Text must be fed in whole lines (each ending in a newline) so that
 * two-character tokens such as `--` are never split between calls.
 */
internal class StatementSplitter {
    private enum class State { NORMAL, STRING, IDENTIFIER, LINE_COMMENT, BLOCK_COMMENT }

    private val buffer = StringBuilder()
    private var state = State.NORMAL
    private var hasContent = false
    private var startLine = 1
    private var line = 1

    /** True between statements: a line that starts with a dot is then a shell command. */
    val isIdle: Boolean get() = state == State.NORMAL && !hasContent

    fun feed(text: String): List<Statement> {
        val done = ArrayList<Statement>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            var width = 1
            if (state == State.NORMAL && buffer.isEmpty() && c.isWhitespace()) {
                if (c == '\n') line++
                i++
                continue
            }
            when (state) {
                State.NORMAL -> when {
                    c == ';' -> {
                        finishStatement(done)
                        i++
                        continue
                    }
                    c == '\'' -> { hasContent = true; state = State.STRING }
                    c == '"' -> { hasContent = true; state = State.IDENTIFIER }
                    c == '-' && next == '-' -> { state = State.LINE_COMMENT; width = 2 }
                    c == '/' && next == '*' -> { state = State.BLOCK_COMMENT; width = 2 }
                    !c.isWhitespace() -> hasContent = true
                }
                State.STRING -> if (c == '\'') state = State.NORMAL
                State.IDENTIFIER -> if (c == '"') state = State.NORMAL
                State.LINE_COMMENT -> if (c == '\n') state = State.NORMAL
                State.BLOCK_COMMENT -> if (c == '*' && next == '/') { state = State.NORMAL; width = 2 }
            }
            if (buffer.isEmpty()) startLine = line
            buffer.append(text, i, i + width)
            if (c == '\n') line++
            i += width
        }
        if (isIdle) buffer.setLength(0)
        return done
    }

    /** Number of the line the next fed text starts on, counting from 1. */
    val currentLine: Int get() = line

    /** Skips a line that was consumed as a shell command rather than fed. */
    fun skipLine() {
        line++
    }

    /** The statement still pending at the end of the input, which has no semicolon, if any. */
    fun finish(): Statement? {
        val statement = if (hasContent) Statement(buffer.toString().trimEnd(), startLine) else null
        buffer.setLength(0)
        hasContent = false
        state = State.NORMAL
        return statement
    }

    private fun finishStatement(done: MutableList<Statement>) {
        if (hasContent) done += Statement(buffer.toString().trimEnd(), startLine)
        buffer.setLength(0)
        hasContent = false
    }
}
