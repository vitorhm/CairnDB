package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Database
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest : SqlTestBase() {
    private fun Database.plan(sql: String, vararg params: Any?): String = rows("EXPLAIN $sql", *params).single().single() as String

    private fun Database.withTables(): Database = also {
        it.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
        it.execute("CREATE TABLE p (a INTEGER, b TEXT, c INTEGER, PRIMARY KEY (a, b))")
        it.execute("CREATE TABLE heap (a INTEGER, b TEXT)")
        it.execute("CREATE TABLE named (name TEXT PRIMARY KEY)")
    }

    @Test
    fun `equality on the whole key is a point lookup`() {
        open().withTables().use { db ->
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("SELECT * FROM t WHERE id = 5"))
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("SELECT * FROM t WHERE 5 = id"))
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("SELECT * FROM t WHERE id = ?", 5))
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("SELECT * FROM t WHERE v = 1 AND id = 5"))
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("DELETE FROM t WHERE id = 5"))
            assertEquals("SEARCH t USING PRIMARY KEY (id=?)", db.plan("UPDATE t SET v = 1 WHERE id = 5"))
            assertEquals("SEARCH named USING PRIMARY KEY (name=?)", db.plan("SELECT * FROM named WHERE name = 'x'"))
            assertEquals("SEARCH p USING PRIMARY KEY (a=? AND b=?)", db.plan("SELECT * FROM p WHERE b = 'x' AND a = 1"))
        }
    }

    @Test
    fun `comparisons and between become ranges`() {
        open().withTables().use { db ->
            assertEquals("SEARCH t USING PRIMARY KEY (id>?)", db.plan("SELECT * FROM t WHERE id > 5"))
            assertEquals("SEARCH t USING PRIMARY KEY (id<?)", db.plan("SELECT * FROM t WHERE 5 > id"))
            assertEquals("SEARCH t USING PRIMARY KEY (id>=? AND id<?)", db.plan("SELECT * FROM t WHERE id >= 5 AND id < 9"))
            assertEquals("SEARCH t USING PRIMARY KEY (id>=? AND id<=?)", db.plan("SELECT * FROM t WHERE id BETWEEN 1 AND 3"))
        }
    }

    @Test
    fun `a prefix of a composite key can be searched`() {
        open().withTables().use { db ->
            assertEquals("SEARCH p USING PRIMARY KEY (a=?)", db.plan("SELECT * FROM p WHERE a = 1"))
            assertEquals("SEARCH p USING PRIMARY KEY (a=?)", db.plan("SELECT * FROM p WHERE a = 1 AND c = 2"))
            assertEquals("SEARCH p USING PRIMARY KEY (a=? AND b>?)", db.plan("SELECT * FROM p WHERE a = 1 AND b > 'x'"))
            assertEquals("SEARCH p USING PRIMARY KEY (a>?)", db.plan("SELECT * FROM p WHERE a > 1 AND b = 'x'"))
        }
    }

    @Test
    fun `conditions that cannot be turned into key bounds scan the table`() {
        open().withTables().use { db ->
            val scans = listOf(
                "SELECT * FROM t WHERE v = 1",
                "SELECT * FROM t WHERE id + 0 = 5",
                "SELECT * FROM t WHERE id = 1 OR id = 2",
                "SELECT * FROM t WHERE NOT id = 1",
                "SELECT * FROM t WHERE id <> 5",
                "SELECT * FROM t WHERE id IN (1, 2)",
                "SELECT * FROM t WHERE id IS NULL",
                "SELECT * FROM t WHERE id = v",
                "SELECT * FROM t WHERE id = 2.5",
                "SELECT * FROM t WHERE id = NULL",
                "SELECT * FROM t",
                "SELECT * FROM p WHERE b = 'x'",
                "SELECT * FROM p WHERE c = 1",
                "SELECT * FROM heap WHERE a = 1",
                "DELETE FROM t",
            )
            for (sql in scans) {
                val expected = "SCAN " + sql.substringAfter(" FROM ").substringBefore(" ").trim()
                assertEquals(expected, db.plan(sql), sql)
            }
        }
    }

    @Test
    fun `explain reports constant selects and rejects other statements`() {
        open().withTables().use { db ->
            assertEquals("CONSTANT ROW", db.plan("SELECT 1"))
            db.fails("EXPLAIN INSERT INTO t VALUES (1, 1)")
            db.fails("EXPLAIN SELECT * FROM nope")
        }
    }

    @Test
    fun `explain does not run the statement`() {
        open().withTables().use { db ->
            db.execute("INSERT INTO t VALUES (1, 1)")
            db.rows("EXPLAIN DELETE FROM t")
            db.rows("EXPLAIN UPDATE t SET v = 2")
            assertEquals(listOf<Any?>(1L), db.column("SELECT v FROM t"))
        }
    }

    @Test
    fun `key bounds are as tight as the conditions allow`() {
        val one = KeyCodec.encode(1L, ColumnType.INTEGER)
        val five = KeyCodec.encode(5L, ColumnType.INTEGER)
        val x = KeyCodec.encode("x", ColumnType.TEXT)
        val simple = TableSchema("t", listOf(Column("id", ColumnType.INTEGER, true)), listOf(0))
        val pair = TableSchema("p", listOf(Column("a", ColumnType.INTEGER, true), Column("b", ColumnType.TEXT, true)), listOf(0, 1))
        fun cond(op: BinaryOp, column: Int, name: String, value: Any) = Binary(op, BoundColumn(column, name), Literal(value))
        fun and(vararg terms: Expr): Expr = terms.reduce { l, r -> Binary(BinaryOp.AND, l, r) }
        fun range(schema: TableSchema, where: Expr): AccessPath.KeyRange = assertIs<AccessPath.KeyRange>(Planner.plan(schema, where))
        fun assertBounds(from: ByteArray?, to: ByteArray?, path: AccessPath.KeyRange) {
            assertEquals(from?.toList(), path.from?.toList(), "from")
            assertEquals(to?.toList(), path.to?.toList(), "to")
        }

        assertBounds(KeyCodec.prefixEnd(five), null, range(simple, cond(BinaryOp.GT, 0, "id", 5L)))
        assertBounds(five, null, range(simple, cond(BinaryOp.GE, 0, "id", 5L)))
        assertBounds(null, five, range(simple, cond(BinaryOp.LT, 0, "id", 5L)))
        assertBounds(null, KeyCodec.prefixEnd(five), range(simple, cond(BinaryOp.LE, 0, "id", 5L)))
        assertBounds(five, KeyCodec.prefixEnd(five), range(simple, and(cond(BinaryOp.GE, 0, "id", 5L), cond(BinaryOp.LE, 0, "id", 5L))))
        assertBounds(five, KeyCodec.prefixEnd(KeyCodec.encode(9L, ColumnType.INTEGER)), range(simple, and(cond(BinaryOp.GE, 0, "id", 5L), cond(BinaryOp.GE, 0, "id", 1L), cond(BinaryOp.LE, 0, "id", 9L), cond(BinaryOp.LE, 0, "id", 20L))))
        assertBounds(one, KeyCodec.prefixEnd(one), range(pair, cond(BinaryOp.EQ, 0, "a", 1L)))
        assertBounds(one + x, KeyCodec.prefixEnd(one), range(pair, and(cond(BinaryOp.EQ, 0, "a", 1L), cond(BinaryOp.GE, 1, "b", "x"))))
        assertBounds(one, one + x, range(pair, and(cond(BinaryOp.EQ, 0, "a", 1L), cond(BinaryOp.LT, 1, "b", "x"))))
        assertBounds(KeyCodec.prefixEnd(one), null, range(pair, and(cond(BinaryOp.GT, 0, "a", 1L), cond(BinaryOp.EQ, 1, "b", "x"))))
        assertContentEquals(one + x, assertIs<AccessPath.KeyLookup>(Planner.plan(pair, and(cond(BinaryOp.EQ, 1, "b", "x"), cond(BinaryOp.EQ, 0, "a", 1L)))).key)
        assertIs<AccessPath.FullScan>(Planner.plan(pair, cond(BinaryOp.EQ, 1, "b", "x")))
    }

    @Test
    fun `contradictory and boundary ranges are handled`() {
        open().withTables().use { db ->
            val present = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 5L, Long.MAX_VALUE)
            present.forEach { db.execute("INSERT INTO t VALUES (?, 0)", it) }
            fun ids(where: String) = db.column("SELECT id FROM t WHERE $where")
            assertEquals(emptyList(), ids("id > 5 AND id < 3"))
            assertEquals(emptyList(), ids("id >= 5 AND id < 5"))
            assertEquals(listOf<Any?>(5L), ids("id >= 5 AND id <= 5"))
            assertEquals(emptyList(), ids("id > 5 AND id < 6"))
            assertEquals(emptyList(), ids("id = 3 AND id = 4"))
            assertEquals(emptyList(), ids("id = 5 AND id > 5"))
            assertEquals(emptyList(), ids("id BETWEEN 9 AND 1"))
            assertEquals(emptyList(), ids("id > 9223372036854775807"))
            assertEquals(emptyList(), ids("id < -9223372036854775808"))
            assertEquals(listOf<Any?>(Long.MAX_VALUE), ids("id >= 9223372036854775807"))
            assertEquals(listOf<Any?>(Long.MIN_VALUE), ids("id <= -9223372036854775808"))
            assertEquals(listOf<Any?>(Long.MAX_VALUE), ids("id > 5"))
            assertEquals(listOf<Any?>(Long.MIN_VALUE), ids("id < -1"))
            assertEquals(listOf<Any?>(-1L, 0L, 1L), ids("id > -9223372036854775808 AND id < 5"))
            assertEquals(listOf<Any?>(0L, 1L, 5L, Long.MAX_VALUE), ids("id >= 0 AND id > -5"))
            assertEquals(listOf<Any?>(Long.MIN_VALUE, -1L, 0L, 1L, 5L), ids("id < 9223372036854775807"))
            assertEquals(listOf<Any?>(5L), ids("id = 5"))
            assertEquals(emptyList(), ids("id = 4"))
        }
    }

    @Test
    fun `exclusive bounds skip every row that shares the bound value`() {
        open().withTables().use { db ->
            db.execute("INSERT INTO p VALUES (1, 'a', 0), (1, 'b', 0), (2, 'a', 0), (2, 'b', 0), (3, 'a', 0)")
            db.execute("INSERT INTO named VALUES ('a'), ('ab'), ('b'), (?)", "a\u0000")
            assertEquals(listOf<Any?>(2L, 2L, 3L), db.column("SELECT a FROM p WHERE a > 1"))
            assertEquals(listOf<Any?>(1L, 1L), db.column("SELECT a FROM p WHERE a < 2"))
            assertEquals(listOf<Any?>(1L, 1L, 2L, 2L), db.column("SELECT a FROM p WHERE a <= 2"))
            assertEquals(listOf<Any?>("b"), db.column("SELECT b FROM p WHERE a = 2 AND b > 'a'"))
            assertEquals(listOf<Any?>("a"), db.column("SELECT b FROM p WHERE a = 2 AND b < 'b'"))
            assertEquals(listOf<Any?>("a\u0000", "ab", "b"), db.column("SELECT name FROM named WHERE name > 'a'"))
            assertEquals(listOf<Any?>("a", "a\u0000"), db.column("SELECT name FROM named WHERE name < 'ab'"))
            assertEquals(listOf<Any?>("a", "a\u0000", "ab"), db.column("SELECT name FROM named WHERE name <= 'ab'"))
        }
    }

    @Test
    fun `real keys with integer constants`() {
        open().use { db ->
            db.execute("CREATE TABLE r (x REAL PRIMARY KEY)")
            listOf(-2.5, -1.0, 0.0, 1.0, 2.5, 9007199254740993.0).forEach { db.execute("INSERT INTO r VALUES (?)", it) }
            assertEquals(listOf<Any?>(1.0), db.column("SELECT x FROM r WHERE x = 1"))
            assertEquals(listOf<Any?>(1.0, 2.5, 9007199254740992.0), db.column("SELECT x FROM r WHERE x >= 1"))
            assertEquals(listOf<Any?>(-2.5, -1.0, 0.0), db.column("SELECT x FROM r WHERE x < 1"))
            assertEquals(listOf<Any?>(2.5, 9007199254740992.0), db.column("SELECT x FROM r WHERE x > 2"))
            assertEquals(emptyList(), db.column("SELECT x FROM r WHERE x = 9007199254740993"))
            assertEquals(listOf<Any?>(9007199254740992.0), db.column("SELECT x FROM r WHERE x >= 9007199254740992"))
            assertEquals(listOf<Any?>(-2.5), db.column("SELECT x FROM r WHERE x < -2"))
        }
    }

    @Test
    fun `type mismatches on key columns still fail`() {
        open().withTables().use { db ->
            db.execute("INSERT INTO t VALUES (1, 1)")
            db.fails("SELECT * FROM t WHERE id = 'x'")
            db.execute("INSERT INTO named VALUES ('a')")
            db.fails("SELECT * FROM named WHERE name = 1")
            assertEquals(emptyList(), db.rows("SELECT * FROM t WHERE id = 1.5"))
            assertEquals(1, db.rows("SELECT * FROM t WHERE id = 1.0").size)
        }
    }

    private class ColumnSpec(val name: String, val disguise: (String) -> String, val constant: (Random) -> Any)

    private class Workload(val table: String, val specs: List<ColumnSpec>)

    private val textAlphabet = listOf("", "a", "b", "\u0000", "\u00e9")

    private fun randomName(random: Random): String = buildString { repeat(random.nextInt(0, 3)) { append(textAlphabet.random(random)) } }

    private fun integerConstant(small: Int): (Random) -> Any = { random ->
        when (random.nextInt(12)) {
            0 -> Long.MAX_VALUE
            1 -> Long.MIN_VALUE
            2 -> Long.MAX_VALUE - 1
            3 -> Long.MIN_VALUE + 1
            4 -> listOf(2.5, -0.5, 3.0, 0.0, -0.0, 1e300, Double.NEGATIVE_INFINITY)[random.nextInt(7)]
            else -> random.nextInt(-small, small + 1).toLong()
        }
    }

    private fun realConstant(random: Random): Any = when (random.nextInt(8)) {
        0 -> listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, 9007199254740993L, Long.MAX_VALUE)[random.nextInt(5)]
        1 -> random.nextInt(-3, 4).toLong()
        else -> random.nextInt(-8, 9) / 2.0
    }

    private fun textConstant(random: Random): Any =
        if (random.nextInt(10) == 0) listOf("\uD83D", "\uDE00", "a\uD83D", "\uD83Da")[random.nextInt(4)] else randomName(random)

    private fun buildPredicate(random: Random, workload: Workload, params: MutableList<Any>): String {
        fun atom(): String {
            val spec = workload.specs.random(random)
            val column = "{${spec.name}}"
            val op = listOf("=", "<", "<=", ">", ">=", "<>").random(random)
            return when (random.nextInt(8)) {
                0 -> {
                    params += spec.constant(random)
                    params += spec.constant(random)
                    "$column BETWEEN ? AND ?"
                }
                1 -> {
                    params += spec.constant(random)
                    "? $op $column"
                }
                else -> {
                    params += spec.constant(random)
                    "$column $op ?"
                }
            }
        }
        val parts = ArrayList<String>()
        repeat(random.nextInt(1, 5)) {
            parts += if (random.nextInt(6) == 0) "(${atom()} OR ${atom()})" else if (random.nextInt(12) == 0) "NOT ${atom()}" else atom()
        }
        return parts.joinToString(" AND ")
    }

    private fun render(template: String, workload: Workload, disguised: Boolean): String =
        workload.specs.fold(template) { sql, spec -> sql.replace("{${spec.name}}", if (disguised) spec.disguise(spec.name) else spec.name) }

    private class Rollback : RuntimeException()

    private fun checkEquivalence(db: Database, workload: Workload, iterations: Int, seed: Int) {
        val random = Random(seed)
        var searches = 0
        repeat(iterations) { round ->
            val params = ArrayList<Any>()
            val template = buildPredicate(random, workload, params)
            val planned = render(template, workload, disguised = false)
            val scanned = render(template, workload, disguised = true)
            val args = params.toTypedArray()

            assertEquals("SCAN ${workload.table}", db.plan("SELECT * FROM ${workload.table} WHERE $scanned", *args), scanned)
            if (db.plan("SELECT * FROM ${workload.table} WHERE $planned", *args).startsWith("SEARCH")) searches++

            val expected = db.rows("SELECT * FROM ${workload.table} WHERE $scanned", *args)
            assertEquals(expected, db.rows("SELECT * FROM ${workload.table} WHERE $planned", *args), "round $round: $planned with ${params.toList()}")

            if (round % 4 == 0) {
                val total = db.rows("SELECT * FROM ${workload.table}")
                assertFailsWith<Rollback> {
                    db.transaction {
                        assertEquals(expected.size, db.update("DELETE FROM ${workload.table} WHERE $planned", *args), "round $round: delete $planned")
                        assertEquals(total.size - expected.size, db.rows("SELECT * FROM ${workload.table}").size)
                        throw Rollback()
                    }
                }
            }
        }
        assertTrue(searches > iterations / 3, "only $searches of $iterations generated predicates used the key")
    }

    @Test
    fun `planned queries return what a full scan returns - single integer key`() {
        open().use { db ->
            db.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, v INTEGER)")
            val random = Random(1)
            val ids = (-6..6).filter { random.nextInt(3) != 0 }.map { it.toLong() } +
                listOf(Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1)
            ids.forEach { db.execute("INSERT INTO a VALUES (?, 0)", it) }
            checkEquivalence(db, Workload("a", listOf(ColumnSpec("id", { "($it + 0)" }, integerConstant(7)))), 600, 11)
        }
    }

    @Test
    fun `planned queries return what a full scan returns - composite integer key`() {
        open().use { db ->
            db.execute("CREATE TABLE b (x INTEGER, y INTEGER, z INTEGER, PRIMARY KEY (x, y))")
            val random = Random(2)
            for (x in -3..3) for (y in -3..3) if (random.nextInt(4) != 0) db.execute("INSERT INTO b VALUES (?, ?, 0)", x, y)
            for (x in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) for (y in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) db.execute("INSERT INTO b VALUES (?, ?, 0)", x, y)
            val workload = Workload(
                "b",
                listOf(
                    ColumnSpec("x", { "($it + 0)" }, integerConstant(4)),
                    ColumnSpec("y", { "($it + 0)" }, integerConstant(4)),
                    ColumnSpec("z", { "($it + 0)" }, integerConstant(1)),
                ),
            )
            checkEquivalence(db, workload, 1000, 12)
        }
    }

    @Test
    fun `planned queries return what a full scan returns - text and integer composite key`() {
        open().use { db ->
            db.execute("CREATE TABLE c (name TEXT, n INTEGER, PRIMARY KEY (name, n))")
            val random = Random(3)
            repeat(80) {
                val name = randomName(random)
                val n = random.nextInt(-2, 3)
                if (db.scalar("SELECT COUNT(*) FROM c WHERE name = ? AND n = ?", name, n) == 0L) db.execute("INSERT INTO c VALUES (?, ?)", name, n)
            }
            val workload = Workload(
                "c",
                listOf(ColumnSpec("name", { "($it || '')" }, ::textConstant), ColumnSpec("n", { "($it + 0)" }, integerConstant(3))),
            )
            checkEquivalence(db, workload, 1000, 13)
        }
    }

    @Test
    fun `planned queries return what a full scan returns - real key`() {
        open().use { db ->
            db.execute("CREATE TABLE d (r REAL PRIMARY KEY)")
            val values = (-8..8).map { it / 2.0 } + listOf(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1e300, -1e300, 9007199254740992.0, Double.MIN_VALUE)
            val random = Random(4)
            values.filter { random.nextInt(4) != 0 }.forEach { db.execute("INSERT INTO d VALUES (?)", it) }
            checkEquivalence(db, Workload("d", listOf(ColumnSpec("r", { "($it + 0)" }, ::realConstant))), 800, 14)
        }
    }
}
