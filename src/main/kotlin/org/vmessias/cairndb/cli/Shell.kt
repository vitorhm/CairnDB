package org.vmessias.cairndb.cli

import org.vmessias.cairndb.DatabaseException
import org.vmessias.cairndb.sql.Lexer
import org.vmessias.cairndb.sql.SqlException
import org.vmessias.cairndb.sql.SqlResult
import org.vmessias.cairndb.sql.TokenKind
import java.io.BufferedReader
import java.io.IOException
import java.io.PrintStream
import java.io.StringReader
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Reads statements and dot commands, runs them against a [Session] and prints the outcome.
 * Results go to [out], errors to [err]. Failures never throw: they are reported and counted in [errors].
 */
internal class Shell(
    private val session: Session,
    private val out: PrintStream,
    private val err: PrintStream,
    private var mode: OutputMode,
    private var headers: Boolean,
    private val bail: Boolean,
) {
    private var timer = false
    private var quit = false
    private var depth = 0

    var errors = 0
        private set

    /** True once `.quit` was typed or, with `--bail`, a statement failed. */
    val shouldStop: Boolean get() = quit || (bail && errors > 0)

    /**
     * Runs everything [reader] yields. [source] names it in error messages; with [prompt] set,
     * each line is asked for with a prompt, as in an interactive session.
     */
    fun run(reader: BufferedReader, source: String, prompt: Boolean) {
        val splitter = StatementSplitter()
        while (!shouldStop) {
            if (prompt) {
                out.print(if (!splitter.isIdle) "...> " else if (session.inTransaction) "sql*> " else "sql> ")
                out.flush()
            }
            val line = reader.readLine()
            if (line == null) {
                if (prompt) out.line()
                break
            }
            if (splitter.isIdle && line.trimStart().startsWith(".")) {
                val number = splitter.currentLine
                splitter.skipLine()
                guarded(Location(source, number, prompt), null) { command(line.trim()) }
                continue
            }
            for (statement in splitter.feed(line + "\n")) {
                if (shouldStop) break
                execute(statement, Location(source, statement.line, prompt))
            }
        }
        if (!shouldStop) splitter.finish()?.let { execute(it, Location(source, it.line, prompt)) }
    }

    fun runText(text: String, source: String) = run(BufferedReader(StringReader(text)), source, prompt = false)

    fun runFile(path: Path) {
        try {
            readFile(path)
        } catch (e: CliException) {
            errors++
            err.println("Error: ${e.message}")
        }
    }

    /** Called when all input is consumed: a transaction still open then is rolled back. */
    fun finish(interactive: Boolean) {
        if (!session.inTransaction) return
        session.rollback()
        err.println("Warning: the transaction still open at the end of the input was rolled back")
        if (!interactive) errors++
    }

    /** Output uses `\n` on every platform so that results and dumps are the same everywhere. */
    private fun PrintStream.line(text: String = "") {
        print(text)
        print('\n')
    }

    private class Location(val source: String, val line: Int, val interactive: Boolean)

    // ---- statements

    private fun execute(statement: Statement, location: Location) = guarded(location, statement) {
        if (transactionControl(statement.text)) return@guarded
        val started = System.nanoTime()
        val result = session.run { it.execute(statement.text) }
        show(result, statement.text)
        if (timer) out.line(String.format(Locale.ROOT, "Run time: %.3f s", (System.nanoTime() - started) / 1e9))
    }

    /** BEGIN, COMMIT, END and ROLLBACK are the shell's business; the SQL layer has no such statements. */
    private fun transactionControl(sql: String): Boolean {
        val tokens = try {
            Lexer(sql).tokenize().dropLast(1)
        } catch (e: SqlException) {
            return false
        }
        if (tokens.isEmpty() || tokens.any { it.kind != TokenKind.IDENTIFIER || it.quoted }) return false
        val words = tokens.map { it.text.uppercase() }
        if (words.size > 2 || words.size == 2 && words[1] != "TRANSACTION") return false
        when (words[0]) {
            "BEGIN" -> session.begin()
            "COMMIT", "END" -> try {
                session.commit()
            } catch (e: DatabaseException) {
                throw CliException("Commit failed and the transaction was rolled back: ${e.message}")
            }
            "ROLLBACK" -> session.rollback()
            else -> return false
        }
        if (mode == OutputMode.TABLE) out.line("OK")
        return true
    }

    private fun show(result: SqlResult, sql: String) {
        if (result.columns.isNotEmpty()) {
            out.print(ResultFormatter.format(result, mode, headers))
            if (mode == OutputMode.TABLE) out.line(count(result.rows.size, "row"))
        } else if (mode == OutputMode.TABLE) {
            val keyword = Lexer(sql).tokenize().first().text.uppercase()
            out.line(
                when (keyword) {
                    "INSERT", "UPDATE", "DELETE" ->
                        "${result.rowsAffected} row${if (result.rowsAffected == 1) "" else "s"} affected" +
                            (result.lastInsertId?.let { " (last id $it)" } ?: "")
                    else -> "OK"
                },
            )
        }
    }

    private fun count(n: Int, noun: String) = if (n == 1) "($n $noun)" else "($n ${noun}s)"

    // ---- dot commands

    private fun command(text: String) {
        val body = text.removeSuffix(";").trim()
        val name = body.substringBefore(' ').lowercase()
        val argument = body.substringAfter(' ', "").trim()
        when (name) {
            ".help" -> out.print(HELP)
            ".quit", ".exit" -> quit = true
            ".tables" -> session.run { it.tables() }.forEach { out.line(it.name) }
            ".schema" -> tablesNamed(argument).forEach { out.line(it.createStatement() + ";") }
            ".dump" -> {
                val tables = tablesNamed(argument)
                session.run { Dumper.dump(it, tables, out) }
            }
            ".mode" -> if (argument.isEmpty()) {
                out.line("mode: ${mode.label}")
            } else {
                mode = OutputMode.parse(argument)
                    ?: throw CliException("Unknown mode '$argument' (use ${OutputMode.entries.joinToString(", ") { it.label }})")
            }
            ".headers" -> headers = toggle(argument, headers, "headers")
            ".timer" -> timer = toggle(argument, timer, "timer")
            ".read" -> {
                if (argument.isEmpty()) throw CliException("Usage: .read FILE")
                readFile(Path.of(argument.removeSurrounding("\"").removeSurrounding("'")))
            }
            ".verify" -> {
                session.run { it.verify() }
                out.line("ok")
            }
            else -> throw CliException("Unknown command: $name (try .help)")
        }
    }

    private fun tablesNamed(name: String) = session.run { db ->
        if (name.isEmpty()) db.tables() else listOf(db.table(name) ?: throw CliException("No such table: $name"))
    }

    private fun toggle(argument: String, current: Boolean, what: String): Boolean = when (argument.lowercase()) {
        "" -> current.also { out.line("$what: ${if (it) "on" else "off"}") }
        "on", "1", "true", "yes" -> true
        "off", "0", "false", "no" -> false
        else -> throw CliException("Expected on or off, not '$argument'")
    }

    private fun readFile(path: Path) {
        if (depth >= MAX_READ_DEPTH) throw CliException("Too many nested .read commands")
        if (!Files.isRegularFile(path)) throw CliException("Cannot read $path")
        val reader = try {
            Files.newBufferedReader(path, Charsets.UTF_8)
        } catch (e: IOException) {
            throw CliException("Cannot read $path: ${e.message}")
        }
        depth++
        try {
            reader.use { run(it, path.toString(), prompt = false) }
        } catch (e: CharacterCodingException) {
            throw CliException("$path is not valid UTF-8")
        } finally {
            depth--
        }
    }

    // ---- errors

    private inline fun guarded(location: Location, statement: Statement?, block: () -> Unit) {
        try {
            block()
        } catch (e: SqlException) {
            report(location, e.message, statement, e.position)
        } catch (e: Exception) {
            report(location, e.message ?: e::class.simpleName, null, null)
        }
    }


    private fun report(location: Location, message: String?, statement: Statement?, position: Int?) {
        errors++
        val where = if (location.interactive) "" else "${location.source}:${location.line}: "
        val hint = if (session.inTransaction && message?.contains("aborted by an earlier error") == true) {
            " (use ROLLBACK to end the transaction)"
        } else {
            ""
        }
        err.println("${where}Error: $message$hint")
        if (statement != null && position != null) {
            val text = statement.text
            val at = position.coerceIn(0, text.length)
            val start = text.lastIndexOf('\n', at - 1) + 1
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            err.println("  " + text.substring(start, end).replace('\t', ' '))
            err.println("  " + " ".repeat(at - start) + "^")
        }
    }

    private companion object {
        const val MAX_READ_DEPTH = 16

        val HELP = """
            .help                Show this help
            .quit, .exit         Leave the shell
            .tables              List the tables
            .schema [TABLE]      Show the CREATE TABLE statements
            .dump [TABLE]        Write SQL that recreates the tables and their rows
            .read FILE           Run the statements and commands in FILE
            .mode [MODE]         Show or set the output mode: table, list, csv or json
            .headers [on|off]    Show or set whether column names are printed
            .timer [on|off]      Show or set whether the run time of statements is printed
            .verify              Check the integrity of the whole database file
            BEGIN, COMMIT, ROLLBACK
                                 Group statements into one all-or-nothing transaction
            Statements end with a semicolon and may span several lines.

        """.trimIndent()
    }
}
