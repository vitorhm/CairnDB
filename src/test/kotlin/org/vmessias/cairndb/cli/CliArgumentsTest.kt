package org.vmessias.cairndb.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgumentsTest {
    private fun parse(vararg args: String) = CliArguments.parse(args.toList())

    @Test
    fun `defaults`() {
        val options = parse("app.db")
        assertEquals(Path.of("app.db"), options.file)
        assertTrue(options.inputs.isEmpty())
        assertEquals(OutputMode.TABLE, options.mode)
        assertTrue(options.headers)
        assertFalse(options.bail)
        assertFalse(options.interactive)
        assertNull(options.pageSize)
        assertTrue(options.syncOnCommit)
    }

    @Test
    fun `options in any position, values separate or joined by equals`() {
        val options = parse("-c", "SELECT 1", "app.db", "--mode=csv", "-f", "a.sql", "--command=SELECT 2", "--page-size", "512",
            "--no-headers", "--bail", "--no-sync", "-i")
        assertEquals(Path.of("app.db"), options.file)
        assertEquals(OutputMode.CSV, options.mode)
        assertEquals(512, options.pageSize)
        assertFalse(options.headers)
        assertTrue(options.bail && options.interactive && !options.syncOnCommit)
        val inputs = options.inputs
        assertEquals(3, inputs.size)
        assertEquals("SELECT 1", assertIs<Input.Sql>(inputs[0]).text)
        assertEquals(Path.of("a.sql"), assertIs<Input.Script>(inputs[1]).path)
        assertEquals("SELECT 2", assertIs<Input.Sql>(inputs[2]).text)
    }

    @Test
    fun `extra positional arguments are SQL`() {
        val options = parse("app.db", "SELECT 1", "SELECT 2")
        assertEquals(listOf("SELECT 1", "SELECT 2"), options.inputs.map { assertIs<Input.Sql>(it).text })
    }

    @Test
    fun `a double dash ends the options`() {
        val options = parse("--", "-weird.db", "-c")
        assertEquals(Path.of("-weird.db"), options.file)
        assertEquals("-c", assertIs<Input.Sql>(options.inputs.single()).text)
    }

    @Test
    fun `help and version do not need a file`() {
        assertTrue(parse("--help").help)
        assertTrue(parse("-h").help)
        assertTrue(parse("--version").version)
    }

    @Test
    fun `mistakes are usage errors`() {
        fun message(vararg args: String) = assertFailsWith<UsageException> { parse(*args) }.message
        assertEquals("Missing database file", message())
        assertEquals("Missing database file", message("-c", "SELECT 1"))
        assertEquals("Unknown option --nope", message("app.db", "--nope"))
        assertEquals("Option -c needs a value", message("app.db", "-c"))
        assertEquals("Option --mode needs a value", message("app.db", "--mode"))
        assertEquals("Invalid page size 'big'", message("app.db", "--page-size", "big"))
        assertEquals("Unknown mode 'xml' (use table, list, csv, json)", message("app.db", "--mode", "xml"))
    }
}
