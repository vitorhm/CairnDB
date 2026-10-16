package org.vmessias.cairndb.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserTest {
    private fun parse(sql: String) = Parser(sql).parse()

    private fun firstItem(sql: String): Expr = ((parse(sql).statement as Select).items.first().expr)!!

    private fun lit(value: Any?) = Literal(value)

    @Test
    fun `arithmetic follows the usual precedence`() {
        assertEquals(
            Binary(BinaryOp.ADD, lit(1L), Binary(BinaryOp.MUL, lit(2L), lit(3L))),
            firstItem("SELECT 1 + 2 * 3"),
        )
        assertEquals(
            Binary(BinaryOp.SUB, Binary(BinaryOp.SUB, lit(10L), lit(3L)), lit(2L)),
            firstItem("SELECT 10 - 3 - 2"),
        )
        assertEquals(
            Binary(BinaryOp.MUL, Binary(BinaryOp.ADD, lit(1L), lit(2L)), lit(3L)),
            firstItem("SELECT (1 + 2) * 3"),
        )
    }

    @Test
    fun `concatenation binds tighter than multiplication`() {
        assertEquals(
            Binary(BinaryOp.MUL, lit(2L), Binary(BinaryOp.CONCAT, lit(3L), lit(4L))),
            firstItem("SELECT 2 * 3 || 4"),
        )
    }

    @Test
    fun `logical operators bind OR loosest then AND then NOT`() {
        val a = ColumnRef(null, "a")
        val b = ColumnRef(null, "b")
        val c = ColumnRef(null, "c")
        assertEquals(
            Binary(BinaryOp.OR, a, Binary(BinaryOp.AND, b, c)),
            firstItem("SELECT a OR b AND c"),
        )
        assertEquals(
            Unary("NOT", Binary(BinaryOp.EQ, a, b)),
            firstItem("SELECT NOT a = b"),
        )
    }

    @Test
    fun `negative literals fold the sign and allow the smallest integer`() {
        assertEquals(lit(-5L), firstItem("SELECT -5"))
        assertEquals(lit(Long.MIN_VALUE), firstItem("SELECT -9223372036854775808"))
        assertEquals(Unary("-", lit(2.5)), firstItem("SELECT -2.5"))
        assertEquals(Binary(BinaryOp.SUB, lit(1L), lit(-1L)), firstItem("SELECT 1 - -1"))
        assertFailsWith<SqlException> { parse("SELECT 9223372036854775808") }
    }

    @Test
    fun `postfix predicates`() {
        val a = ColumnRef(null, "a")
        assertEquals(IsNull(a, true), firstItem("SELECT a IS NOT NULL"))
        assertEquals(Like(a, lit("x%"), true), firstItem("SELECT a NOT LIKE 'x%'"))
        assertEquals(InList(a, listOf(lit(1L), lit(2L)), false), firstItem("SELECT a IN (1, 2)"))
        assertEquals(Between(a, lit(1L), lit(2L), true), firstItem("SELECT a NOT BETWEEN 1 AND 2"))
        assertEquals(
            Binary(BinaryOp.AND, Between(a, lit(1L), lit(2L), false), ColumnRef(null, "b")),
            firstItem("SELECT a BETWEEN 1 AND 2 AND b"),
        )
    }

    @Test
    fun `literals`() {
        assertEquals(lit("it's"), firstItem("SELECT 'it''s'"))
        assertEquals(lit(1000.0), firstItem("SELECT 1e3"))
        assertEquals(lit(0.5), firstItem("SELECT .5"))
        assertEquals(lit(true), firstItem("SELECT TRUE"))
        assertEquals(lit(null), firstItem("SELECT null"))
        assertEquals(lit(byteArrayOf(10, 11)), firstItem("SELECT x'0a0B'"))
        assertEquals(ColumnRef(null, "order"), firstItem("SELECT \"order\""))
        assertEquals(ColumnRef("t", "c"), firstItem("SELECT t.c"))
    }

    @Test
    fun `parameters are numbered in order and counted`() {
        val parsed = parse("SELECT ?, ? + ? FROM t WHERE a = ?")
        assertEquals(4, parsed.parameterCount)
        val select = parsed.statement as Select
        assertEquals(Param(0), select.items[0].expr)
        assertEquals(Binary(BinaryOp.ADD, Param(1), Param(2)), select.items[1].expr)
        assertEquals(Binary(BinaryOp.EQ, ColumnRef(null, "a"), Param(3)), select.where)
    }

    @Test
    fun `comments and trailing semicolons are skipped`() {
        val select = parse("/* lead */ SELECT 1 -- tail\n ;;").statement
        assertIs<Select>(select)
    }

    @Test
    fun `select item text and aliases are kept`() {
        val select = parse("SELECT a + 1 AS next, b,   COUNT( * ) FROM t").statement as Select
        assertEquals("next", select.items[0].alias)
        assertEquals("a + 1", select.items[0].text)
        assertEquals("COUNT( * )", select.items[2].text)
        assertEquals("t", select.table)
    }

    @Test
    fun `create table accepts column and table level keys and type arguments`() {
        val create = parse(
            "CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, name VARCHAR(20) NOT NULL, v DOUBLE PRECISION, b BLOB NULL)",
        ).statement as CreateTable
        assertTrue(create.ifNotExists)
        assertEquals(listOf(ColumnType.INTEGER, ColumnType.TEXT, ColumnType.REAL, ColumnType.BLOB), create.columns.map { it.type })
        assertEquals(listOf(true, false, false, false), create.columns.map { it.primaryKey })
        assertEquals(listOf(false, true, false, false), create.columns.map { it.notNull })

        val composite = parse("CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY (a, b))").statement as CreateTable
        assertEquals(listOf("a", "b"), composite.primaryKey)
    }

    @Test
    fun `statement shapes`() {
        val insert = parse("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y')").statement as Insert
        assertEquals(listOf("a", "b"), insert.columns)
        assertEquals(2, insert.rows.size)

        val update = parse("UPDATE t SET a = a + 1, b = 'z' WHERE a > 1").statement as Update
        assertEquals(listOf("a", "b"), update.assignments.map { it.first })

        val delete = parse("DELETE FROM t").statement as Delete
        assertEquals(null, delete.where)

        val select = parse("SELECT DISTINCT a FROM t WHERE a > 1 GROUP BY a HAVING COUNT(*) > 1 ORDER BY a DESC, 1 LIMIT 5 OFFSET 2").statement as Select
        assertTrue(select.distinct)
        assertEquals(listOf(true, false), select.orderBy.map { it.descending })
        assertEquals(1, select.groupBy.size)

        assertIs<Explain>(parse("EXPLAIN SELECT 1").statement)
        assertIs<DropTable>(parse("DROP TABLE IF EXISTS t").statement)
    }

    @Test
    fun `syntax errors carry the position and a readable message`() {
        val unknown = assertFailsWith<SqlException> { parse("SELEC 1") }
        assertEquals(0, unknown.position)
        assertTrue("SELEC" in unknown.message!!)

        val truncated = assertFailsWith<SqlException> { parse("SELECT 1 +") }
        assertTrue("end of the statement" in truncated.message!!)

        val trailing = assertFailsWith<SqlException> { parse("SELECT 1; SELECT 2") }
        assertEquals(10, trailing.position)

        val unterminated = assertFailsWith<SqlException> { parse("SELECT 'abc") }
        assertEquals(7, unterminated.position)

        assertFailsWith<SqlException> { parse("SELECT 1 /* open") }
        assertFailsWith<SqlException> { parse("SELECT #") }
        assertFailsWith<SqlException> { parse("SELECT 12abc") }
        assertFailsWith<SqlException> { parse("SELECT x'abc'") }
        assertFailsWith<SqlException> { parse("SELECT FROM t") }
        assertFailsWith<SqlException> { parse("SELECT * FROM") }
        assertFailsWith<SqlException> { parse("SELECT (1") }
        assertFailsWith<SqlException> { parse("INSERT INTO t VALUES 1") }
        assertFailsWith<SqlException> { parse("") }
        assertFailsWith<SqlException> { parse("EXPLAIN DROP TABLE t") }
    }

    @Test
    fun `reserved words need quotes to be used as names`() {
        assertFailsWith<SqlException> { parse("CREATE TABLE select (a INTEGER)") }
        assertFailsWith<SqlException> { parse("SELECT a FROM order") }
        assertIs<CreateTable>(parse("CREATE TABLE \"select\" (\"order\" INTEGER)").statement)
        assertIs<CreateTable>(parse("CREATE TABLE t (key TEXT, text TEXT)").statement)
    }

    @Test
    fun `table definitions are validated while parsing`() {
        assertFailsWith<SqlException> { parse("CREATE TABLE t (a FOO)") }
        assertFailsWith<SqlException> { parse("CREATE TABLE t (a)") }
        assertFailsWith<SqlException> { parse("CREATE TABLE t (a INTEGER, PRIMARY KEY (a), PRIMARY KEY (a))") }
        assertFailsWith<SqlException> { parse("SELECT SUM(*) FROM t") }
    }
}
