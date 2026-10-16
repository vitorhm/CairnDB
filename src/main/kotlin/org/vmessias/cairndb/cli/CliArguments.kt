package org.vmessias.cairndb.cli

import java.nio.file.Path

/** Something to run before (or instead of) reading standard input. */
internal sealed interface Input {
    class Sql(val text: String) : Input
    class Script(val path: Path) : Input
}

/** The command line is invalid. */
internal class UsageException(message: String) : Exception(message)

internal class CliOptions(
    val file: Path?,
    val inputs: List<Input>,
    val mode: OutputMode,
    val headers: Boolean,
    val bail: Boolean,
    val interactive: Boolean,
    val pageSize: Int?,
    val syncOnCommit: Boolean,
    val help: Boolean,
    val version: Boolean,
)

internal object CliArguments {
    fun parse(args: List<String>): CliOptions {
        var file: Path? = null
        val inputs = ArrayList<Input>()
        var mode = OutputMode.TABLE
        var headers = true
        var bail = false
        var interactive = false
        var pageSize: Int? = null
        var syncOnCommit = true
        var help = false
        var version = false

        val iterator = args.iterator()
        var optionsEnded = false

        fun value(option: String, inline: String?): String =
            inline ?: if (iterator.hasNext()) iterator.next() else throw UsageException("Option $option needs a value")

        fun positional(arg: String) {
            if (file == null) file = Path.of(arg) else inputs += Input.Sql(arg)
        }

        while (iterator.hasNext()) {
            val arg = iterator.next()
            if (optionsEnded || !arg.startsWith("-") || arg == "-") {
                positional(arg)
                continue
            }
            if (arg == "--") {
                optionsEnded = true
                continue
            }
            val name = if (arg.startsWith("--")) arg.substringBefore('=') else arg
            val inline = if (arg.startsWith("--") && '=' in arg) arg.substringAfter('=') else null
            when (name) {
                "-h", "--help" -> help = true
                "--version" -> version = true
                "-i", "--interactive" -> interactive = true
                "--bail" -> bail = true
                "--no-headers" -> headers = false
                "--no-sync" -> syncOnCommit = false
                "-c", "--command" -> inputs += Input.Sql(value(name, inline))
                "-f", "--file" -> inputs += Input.Script(Path.of(value(name, inline)))
                "-m", "--mode" -> {
                    val text = value(name, inline)
                    mode = OutputMode.parse(text)
                        ?: throw UsageException("Unknown mode '$text' (use ${OutputMode.entries.joinToString(", ") { it.label }})")
                }
                "--page-size" -> {
                    val text = value(name, inline)
                    pageSize = text.toIntOrNull() ?: throw UsageException("Invalid page size '$text'")
                }
                else -> throw UsageException("Unknown option $arg")
            }
        }
        if (file == null && !help && !version) throw UsageException("Missing database file")
        return CliOptions(file, inputs, mode, headers, bail, interactive, pageSize, syncOnCommit, help, version)
    }
}
