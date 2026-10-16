package org.vmessias.cairndb.cli

import org.vmessias.cairndb.Database
import org.vmessias.cairndb.DatabaseException
import org.vmessias.cairndb.DatabaseOptions
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.Charset

internal object Cli {
    private val version: String = Cli::class.java.`package`?.implementationVersion ?: "dev"

    /**
     * Runs the command line [args]. [console] says that standard input is a person at a terminal,
     * which makes the shell interactive when there is nothing else to run. Returns the exit status:
     * 0 on success, 1 if the database could not be opened or a statement failed (unless interactive),
     * 2 for a usage error.
     */
    fun run(args: List<String>, input: InputStream, inputCharset: Charset, out: PrintStream, err: PrintStream, console: Boolean): Int {
        val options = try {
            CliArguments.parse(args)
        } catch (e: UsageException) {
            err.println("cairndb: ${e.message}")
            err.println("Try 'cairndb --help'.")
            return 2
        }
        if (options.help) {
            out.print(USAGE)
            return 0
        }
        if (options.version) {
            out.print("cairndb $version\n")
            return 0
        }
        val file = options.file!!
        val databaseOptions = try {
            DatabaseOptions(
                pageSize = options.pageSize ?: DatabaseOptions.DEFAULT_PAGE_SIZE,
                syncOnCommit = options.syncOnCommit,
            )
        } catch (e: IllegalArgumentException) {
            err.println("cairndb: ${e.message}")
            return 2
        }
        val database = try {
            Database.open(file, databaseOptions)
        } catch (e: DatabaseException) {
            err.println("cairndb: cannot open $file: ${e.message}")
            return 1
        } catch (e: IOException) {
            err.println("cairndb: cannot open $file: ${e.message}")
            return 1
        }

        database.use {
            val session = Session(database)
            session.use {
                val interactive = options.interactive || (options.inputs.isEmpty() && console)
                val shell = Shell(session, out, err, options.mode, options.headers, options.bail)
                if (interactive) out.print("cairndb $version\nConnected to $file. Type .help for help, .quit to leave.\n")
                for (item in options.inputs) {
                    if (shell.shouldStop) break
                    when (item) {
                        is Input.Sql -> shell.runText(item.text, "command")
                        is Input.Script -> shell.runFile(item.path)
                    }
                }
                if (!shell.shouldStop && (options.inputs.isEmpty() || options.interactive)) {
                    shell.run(BufferedReader(InputStreamReader(input, inputCharset)), "stdin", prompt = interactive)
                }
                shell.finish(interactive)
                out.flush()
                return if (!interactive && shell.errors > 0) 1 else 0
            }
        }
    }

    private val USAGE = """
        Usage: cairndb [options] FILE [SQL...]

        Opens the database FILE, creating it if needed, and runs SQL on it. Without -c, -f or SQL
        arguments it reads statements and dot commands from standard input: an interactive shell
        at a terminal, a script otherwise.

        Options:
          -c, --command SQL    Run SQL (statements end with a semicolon); may be repeated
          -f, --file SCRIPT    Run the statements and commands in SCRIPT; may be repeated
          -i, --interactive    Read standard input with prompts, also after -c and -f
          -m, --mode MODE      Output mode: table (default), list, csv or json
              --no-headers     Do not print column names
              --bail           Stop at the first error
              --page-size N    Page size of a new database: a power of two from 512 to 32768
              --no-sync        Skip fsync on commit: faster, but not safe against power loss
          -h, --help           Show this help
              --version        Show the version

        Exit status: 0 on success, 1 if the database cannot be opened or a statement failed
        (not in an interactive session), 2 if the command line is invalid.

    """.trimIndent()
}
