package org.vmessias.cairndb.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementSplitterTest {
    private fun StatementSplitter.texts(text: String): List<String> = feed(text).map { it.text }

    @Test
    fun `one statement per line`() {
        val splitter = StatementSplitter()
        assertEquals(listOf("SELECT 1"), splitter.texts("SELECT 1;\n"))
        assertEquals(listOf("SELECT 2"), splitter.texts("SELECT 2;\n"))
    }

    @Test
    fun `several statements on a line`() {
        assertEquals(listOf("SELECT 1", "SELECT 2", "SELECT 3"), StatementSplitter().texts("SELECT 1; SELECT 2;SELECT 3;\n"))
    }

    @Test
    fun `a statement can span lines and waits for its semicolon`() {
        val splitter = StatementSplitter()
        assertTrue(splitter.isIdle)
        assertEquals(emptyList(), splitter.texts("SELECT a,\n"))
        assertFalse(splitter.isIdle)
        assertEquals(emptyList(), splitter.texts("  b\n"))
        assertEquals(listOf("SELECT a,\n  b\nFROM t"), splitter.texts("FROM t\n;\n"))
        assertTrue(splitter.isIdle)
    }

    @Test
    fun `semicolons inside strings, quoted identifiers and comments do not end a statement`() {
        val splitter = StatementSplitter()
        val sql = "SELECT 'a;b', \"c;d\" -- e;f\n, /* g;h\n;i */ 1 FROM t;\n"
        assertEquals(listOf(sql.removeSuffix(";\n")), splitter.texts(sql))
    }

    @Test
    fun `doubled quotes stay inside the literal`() {
        assertEquals(listOf("INSERT INTO t VALUES ('it''s; fine')"), StatementSplitter().texts("INSERT INTO t VALUES ('it''s; fine');\n"))
        assertEquals(listOf("SELECT \"a\"\";b\""), StatementSplitter().texts("SELECT \"a\"\";b\";\n"))
    }

    @Test
    fun `a string can span lines`() {
        val splitter = StatementSplitter()
        assertEquals(emptyList(), splitter.texts("INSERT INTO t VALUES ('one;\n"))
        assertFalse(splitter.isIdle)
        assertEquals(listOf("INSERT INTO t VALUES ('one;\ntwo')"), splitter.texts("two');\n"))
    }

    @Test
    fun `comments and empty statements produce nothing`() {
        val splitter = StatementSplitter()
        assertEquals(emptyList(), splitter.texts("-- only a comment\n"))
        assertTrue(splitter.isIdle)
        assertEquals(emptyList(), splitter.texts("/* block\n comment */\n"))
        assertTrue(splitter.isIdle)
        assertEquals(emptyList(), splitter.texts(";;  ;\n"))
        assertNull(splitter.finish())
    }

    @Test
    fun `an open block comment is not idle`() {
        val splitter = StatementSplitter()
        splitter.feed("/* never\n")
        assertFalse(splitter.isIdle)
        splitter.feed("closed */\n")
        assertTrue(splitter.isIdle)
    }

    @Test
    fun `finish returns the statement that has no semicolon`() {
        val splitter = StatementSplitter()
        splitter.feed("SELECT 1;\nSELECT 2\n")
        val last = splitter.finish()
        assertEquals("SELECT 2", last?.text)
        assertNull(splitter.finish())
        assertTrue(splitter.isIdle)
    }

    @Test
    fun `finish returns an unterminated string as it is`() {
        val splitter = StatementSplitter()
        splitter.feed("SELECT 'abc\n")
        assertEquals("SELECT 'abc", splitter.finish()?.text)
    }

    @Test
    fun `statements know the line they start on`() {
        val splitter = StatementSplitter()
        val all = splitter.feed("SELECT 1;\n\n  SELECT\n 2;\n-- note\nSELECT 3; SELECT 4;\n")
        assertEquals(listOf("SELECT 1" to 1, "SELECT\n 2" to 3, "-- note\nSELECT 3" to 5, "SELECT 4" to 6), all.map { it.text to it.line })
    }

    @Test
    fun `skipped lines are counted`() {
        val splitter = StatementSplitter()
        splitter.feed("SELECT 1;\n")
        assertEquals(2, splitter.currentLine)
        splitter.skipLine()
        assertEquals(listOf(3), splitter.feed("SELECT 2;\n").map { it.line })
        assertEquals(4, splitter.currentLine)
    }

    @Test
    fun `leading comments are dropped once a dot command follows them`() {
        val splitter = StatementSplitter()
        splitter.feed("-- header\n")
        splitter.skipLine()
        assertEquals(listOf("SELECT 1" to 3), splitter.feed("SELECT 1;\n").map { it.text to it.line })
    }
}
