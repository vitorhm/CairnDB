package org.vmessias.cairndb.cli

import java.io.BufferedOutputStream
import java.io.Console
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.system.exitProcess

/** True if both standard input and output are a terminal (`Console.isTerminal` exists from JDK 22 on). */
private fun atTerminal(): Boolean {
    val console = System.console() ?: return false
    return try {
        Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
    } catch (e: ReflectiveOperationException) {
        true
    }
}

internal fun main(args: Array<String>) {
    val console = atTerminal()
    // At a terminal the JVM already picked the console's encoding; redirected streams are UTF-8.
    val inputCharset = if (console) System.getProperty("stdin.encoding")?.let(Charset::forName) ?: Charset.defaultCharset() else Charsets.UTF_8
    val out = if (console) System.out else PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)), false, Charsets.UTF_8)
    val err = if (console) System.err else PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    val status = Cli.run(args.toList(), System.`in`, inputCharset, out, err, console)
    out.flush()
    exitProcess(status)
}
