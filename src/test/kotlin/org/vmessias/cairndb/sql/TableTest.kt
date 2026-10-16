package org.vmessias.cairndb.sql

import org.vmessias.cairndb.DatabaseException
import org.vmessias.cairndb.storage.CommitStep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableTest : SqlTestBase() {
    @Test
    fun `values of every type round trip and survive a reopen`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, i INTEGER, r REAL, s TEXT, b BLOB)")
            db.execute("INSERT INTO t VALUES (1, -7, 2.5, 'héllo', x'00ff10')")
            db.execute("INSERT INTO t VALUES (2, NULL, NULL, NULL, NULL)")
            db.execute("INSERT INTO t VALUES (3, ?, ?, ?, ?)", Long.MIN_VALUE, -0.0, "", byteArrayOf())
        }
        open().use { db ->
            assertEquals(
                listOf(
                    listOf<Any?>(1L, -7L, 2.5, "héllo", bytes(0, 255, 16)),
                    listOf<Any?>(2L, null, null, null, null),
                    listOf<Any?>(3L, Long.MIN_VALUE, 0.0, "", bytes()),
                ),
                db.rows("SELECT * FROM t"),
            )
            db.verify()
        }
    }

    @Test
    fun `select star keeps the declared column names`() {
        open().use { db ->
            db.execute("CREATE TABLE People (Id INTEGER PRIMARY KEY, FullName TEXT)")
            assertEquals(listOf("Id", "FullName"), db.execute("SELECT * FROM people").columns)
        }
    }

    @Test
    fun `names are case insensitive and quoting allows reserved words`() {
        open().use { db ->
            db.execute("CREATE TABLE \"Order\" (\"select\" INTEGER PRIMARY KEY, Name TEXT)")
            db.execute("INSERT INTO \"order\" (\"SELECT\", NAME) VALUES (1, 'a')")
            assertEquals(listOf(listOf<Any?>(1L, "a")), db.rows("SELECT \"select\", name FROM \"ORDER\""))
            db.fails("CREATE TABLE \"ORDER\" (x INTEGER)")
        }
    }

    @Test
    fun `an integer primary key is assigned when omitted`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            assertEquals(1L, db.execute("INSERT INTO t (v) VALUES ('a')").lastInsertId)
            assertEquals(2L, db.execute("INSERT INTO t (id, v) VALUES (NULL, 'b')").lastInsertId)
            assertEquals(10L, db.execute("INSERT INTO t VALUES (10, 'c')").lastInsertId)
            assertEquals(11L, db.execute("INSERT INTO t (v) VALUES ('d')").lastInsertId)
            val many = db.execute("INSERT INTO t (v) VALUES ('e'), ('f')")
            assertEquals(2, many.rowsAffected)
            assertEquals(13L, many.lastInsertId)
            assertEquals(listOf<Any?>(1L, 2L, 10L, 11L, 12L, 13L), db.column("SELECT id FROM t"))
        }
    }

    @Test
    fun `auto assigned ids are never reused and survive a reopen`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO t VALUES (NULL), (NULL), (NULL)")
            db.execute("DELETE FROM t WHERE id = 3")
        }
        open().use { db ->
            assertEquals(4L, db.execute("INSERT INTO t VALUES (NULL)").lastInsertId)
        }
    }

    @Test
    fun `explicit ids move the counter forward in the same statement`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
            db.execute("INSERT INTO t VALUES (5, 0), (NULL, 1), (3, 2), (NULL, 3)")
            assertEquals(listOf<Any?>(3L, 5L, 6L, 7L), db.column("SELECT id FROM t"))
        }
    }

    @Test
    fun `row ids run out cleanly`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO t VALUES (?)", Long.MAX_VALUE)
            db.fails("INSERT INTO t VALUES (NULL)")
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `tables without a primary key keep insertion order and allow duplicates`() {
        open().use { db ->
            db.execute("CREATE TABLE log (msg TEXT)")
            db.execute("INSERT INTO log VALUES ('b'), ('a'), ('b')")
            assertEquals(4L, db.execute("INSERT INTO log VALUES ('c')").lastInsertId)
            assertEquals(listOf<Any?>("b", "a", "b", "c"), db.column("SELECT msg FROM log"))
            db.execute("DELETE FROM log WHERE msg = 'a'")
            db.execute("INSERT INTO log VALUES ('z')")
            assertEquals(listOf<Any?>("b", "b", "c", "z"), db.column("SELECT msg FROM log"))
            assertEquals(2, db.update("DELETE FROM log WHERE msg = 'b'"))
        }
    }

    @Test
    fun `composite primary keys are unique as a pair and ordered lexicographically`() {
        open().use { db ->
            db.execute("CREATE TABLE t (a INTEGER, b TEXT, v INTEGER, PRIMARY KEY (a, b))")
            db.execute("INSERT INTO t VALUES (2, 'x', 1), (1, 'y', 2), (1, 'x', 3), (-5, 'z', 4), (1, '', 5)")
            assertEquals(
                listOf<Any?>(4L, 5L, 3L, 2L, 1L),
                db.column("SELECT v FROM t"),
            )
            val failure = db.fails("INSERT INTO t VALUES (1, 'x', 9)")
            assertTrue("t.a" in failure.message!! && "t.b" in failure.message!!, failure.message)
            assertEquals(5L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `constraint violations`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 'a', 1)")
            assertTrue("UNIQUE constraint failed: t.id" in db.fails("INSERT INTO t VALUES (1, 'b', 2)").message!!)
            assertTrue("NOT NULL constraint failed: t.name" in db.fails("INSERT INTO t VALUES (2, NULL, 2)").message!!)
            assertTrue("NOT NULL" in db.fails("INSERT INTO t (id) VALUES (3)").message!!)
            db.fails("UPDATE t SET name = NULL")
            db.fails("INSERT INTO t VALUES (4, 'x', 'old')")
            db.fails("INSERT INTO t VALUES (4, 1, 1)")
            db.fails("INSERT INTO t VALUES (4, 'x', 1.5)")
            db.fails("INSERT INTO t VALUES (4, 'x')")
            db.fails("INSERT INTO t VALUES (4, 'x', 1, 2)")
            db.fails("INSERT INTO t (id, id) VALUES (4, 5)")
            db.fails("INSERT INTO t (nope) VALUES (4)")
            db.fails("INSERT INTO t VALUES (4, ?, 1)", Double.NaN)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `primary key columns must be supplied unless the key is a single integer`() {
        open().use { db ->
            db.execute("CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b))")
            db.fails("INSERT INTO t VALUES (1, NULL)")
            db.fails("INSERT INTO t (a) VALUES (1)")
            db.execute("CREATE TABLE s (name TEXT PRIMARY KEY)")
            db.fails("INSERT INTO s VALUES (NULL)")
        }
    }

    @Test
    fun `real columns accept integers and integer columns reject reals`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, r REAL, i INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 3, 3)")
            assertEquals("real", db.scalar("SELECT TYPEOF(r) FROM t"))
            assertEquals("integer", db.scalar("SELECT TYPEOF(i) FROM t"))
            db.fails("INSERT INTO t VALUES (2, 1, 1.0)")
            db.fails("UPDATE t SET i = 2.5")
            db.update("UPDATE t SET r = 4")
            assertEquals(4.0, db.scalar("SELECT r FROM t"))
        }
    }

    @Test
    fun `booleans are stored as integers`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, flag INTEGER)")
            db.execute("INSERT INTO t VALUES (1, ?), (2, ?), (3, TRUE)", true, false)
            assertEquals(listOf<Any?>(1L, 0L, 1L), db.column("SELECT flag FROM t"))
            assertEquals(listOf<Any?>(1L, 3L), db.column("SELECT id FROM t WHERE flag"))
        }
    }

    @Test
    fun `a failing multi row insert stores nothing`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
            db.execute("INSERT INTO t VALUES (2, 'two')")
            db.fails("INSERT INTO t VALUES (1, 'one'), (3, 'three'), (2, 'dup')")
            db.fails("INSERT INTO t VALUES (1, 'one'), (3, NULL)")
            db.fails("INSERT INTO t VALUES (5, 'five'), (5, 'five again')")
            assertEquals(listOf<Any?>(2L), db.column("SELECT id FROM t"))
            db.execute("INSERT INTO t VALUES (1, 'one')")
            assertEquals(2L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `update changes the matching rows`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, n INTEGER, s TEXT)")
            db.execute("INSERT INTO t VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')")
            assertEquals(2, db.update("UPDATE t SET n = n + 1, s = s || '!' WHERE id >= 2"))
            assertEquals(
                listOf(listOf<Any?>(1L, 10L, "a"), listOf<Any?>(2L, 21L, "b!"), listOf<Any?>(3L, 31L, "c!")),
                db.rows("SELECT * FROM t"),
            )
            assertEquals(0, db.update("UPDATE t SET n = 0 WHERE id = 99"))
            assertEquals(3, db.update("UPDATE t SET s = NULL"))
            assertEquals(listOf<Any?>(null, null, null), db.column("SELECT s FROM t"))
        }
    }

    @Test
    fun `update assignments see the old row`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 1, 2)")
            db.execute("UPDATE t SET a = b, b = a")
            assertEquals(listOf(listOf<Any?>(2L, 1L)), db.rows("SELECT a, b FROM t"))
        }
    }

    @Test
    fun `update can change primary keys including swaps`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execute("INSERT INTO t VALUES (1, 'one'), (2, 'two'), (3, 'three')")
            assertEquals(3, db.update("UPDATE t SET id = 4 - id"))
            assertEquals(listOf(listOf<Any?>(1L, "three"), listOf<Any?>(2L, "two"), listOf<Any?>(3L, "one")), db.rows("SELECT * FROM t"))
            assertEquals(3, db.update("UPDATE t SET id = id + 10"))
            assertEquals(listOf<Any?>(11L, 12L, 13L), db.column("SELECT id FROM t"))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t WHERE id = 11"))
        }
    }

    @Test
    fun `a primary key collision in update changes nothing`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execute("INSERT INTO t VALUES (1, 'one'), (2, 'two'), (3, 'three')")
            db.fails("UPDATE t SET id = 2 WHERE id = 1")
            db.fails("UPDATE t SET id = 5")
            db.fails("UPDATE t SET id = NULL WHERE id = 1")
            db.fails("UPDATE t SET v = 'x', v = 'y'")
            db.fails("UPDATE t SET nope = 1")
            assertEquals(listOf<Any?>(1L, 2L, 3L), db.column("SELECT id FROM t"))
            assertEquals(listOf<Any?>("one", "two", "three"), db.column("SELECT v FROM t"))
        }
    }

    @Test
    fun `delete removes the matching rows`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, n INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 1), (2, 2), (3, 1), (4, 2), (5, 1)")
            assertEquals(3, db.update("DELETE FROM t WHERE n = 1"))
            assertEquals(listOf<Any?>(2L, 4L), db.column("SELECT id FROM t"))
            assertEquals(1, db.update("DELETE FROM t WHERE id > 2"))
            assertEquals(1, db.update("DELETE FROM t"))
            assertEquals(0, db.update("DELETE FROM t"))
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `drop table removes it and its storage`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
            db.execute("DROP TABLE t")
            db.fails("SELECT * FROM t")
            db.fails("DROP TABLE t")
            db.execute("DROP TABLE IF EXISTS t")
            assertTrue(db.bucketNames().none { it.startsWith("sql.table.") })
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM t"))
            assertEquals(1L, db.execute("INSERT INTO t (v) VALUES ('fresh')").lastInsertId)
            db.verify()
        }
    }

    @Test
    fun `create table if not exists keeps the existing table`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO t VALUES (1)")
            db.fails("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("CREATE TABLE IF NOT EXISTS t (other TEXT)")
            assertEquals(listOf("id"), db.execute("SELECT * FROM t").columns)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `invalid table definitions are rejected`() {
        open().use { db ->
            db.fails("CREATE TABLE t (a INTEGER, A TEXT)")
            db.fails("CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER PRIMARY KEY)")
            db.fails("CREATE TABLE t (a INTEGER, PRIMARY KEY (b))")
            db.fails("CREATE TABLE t (a INTEGER, PRIMARY KEY (a, a))")
            db.fails("CREATE TABLE t (a INTEGER PRIMARY KEY, PRIMARY KEY (a))")
            db.fails("CREATE TABLE ${"x".repeat(60)} (a INTEGER)")
            assertTrue(db.bucketNames().none { it.startsWith("sql.table.") })
        }
    }

    @Test
    fun `statements on missing tables or columns fail`() {
        open().use { db ->
            db.fails("SELECT * FROM nope")
            db.fails("INSERT INTO nope VALUES (1)")
            db.fails("UPDATE nope SET a = 1")
            db.fails("DELETE FROM nope")
            db.execute("CREATE TABLE t (a INTEGER)")
            db.fails("SELECT b FROM t")
            db.fails("SELECT * FROM t WHERE b = 1")
            db.fails("SELECT other.a FROM t")
            assertEquals(emptyList(), db.rows("SELECT t.a FROM t"))
        }
    }

    @Test
    fun `unpaired surrogates cannot be stored but can be compared`() {
        open().use { db ->
            db.execute("CREATE TABLE t (name TEXT PRIMARY KEY, other TEXT)")
            db.execute("INSERT INTO t VALUES ('a', NULL), (?, NULL)", "\uD83D\uDE00")
            db.fails("INSERT INTO t VALUES (?, NULL)", "\uD83D")
            db.fails("INSERT INTO t VALUES ('b', ?)", "x\uDE00")
            db.fails("UPDATE t SET other = ?", "\uD83D")
            db.fails("UPDATE t SET name = ? WHERE name = 'a'", "\uDE00")
            db.fails("CREATE TABLE \"\uD83D\" (x INTEGER)")
            assertEquals(listOf<Any?>("a"), db.column("SELECT name FROM t WHERE name < ?", "\uD83D"))
            assertEquals(listOf<Any?>("\uD83D\uDE00"), db.column("SELECT name FROM t WHERE name > ?", "\uD83D"))
            assertEquals(listOf<Any?>("a", "\uD83D\uDE00"), db.column("SELECT name FROM t ORDER BY name"))
        }
    }

    @Test
    fun `text keys sort by code point`() {
        open().use { db ->
            db.execute("CREATE TABLE t (name TEXT PRIMARY KEY)")
            val names = listOf("b", "a", "\uD83D\uDE00", "\uFFFF", "", "a\u0000", "a\u0000b", "ab", "\u00e9")
            names.forEach { db.execute("INSERT INTO t VALUES (?)", it) }
            val expected = names.sortedWith { x, y -> x.codePoints().toArray().toList().compareTo(y.codePoints().toArray().toList()) }
            assertEquals(expected, db.column("SELECT name FROM t"))
            assertEquals(expected, db.column("SELECT name FROM t ORDER BY name"))
            assertEquals(expected.reversed(), db.column("SELECT name FROM t ORDER BY name DESC"))
            names.forEach { assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t WHERE name = ?", it)) }
        }
    }

    private fun List<Int>.compareTo(other: List<Int>): Int {
        for (i in 0 until minOf(size, other.size)) if (this[i] != other[i]) return this[i].compareTo(other[i])
        return size.compareTo(other.size)
    }

    @Test
    fun `real keys sort numerically and treat negative zero as zero`() {
        open().use { db ->
            db.execute("CREATE TABLE t (x REAL PRIMARY KEY)")
            val values = listOf(3.5, -1.5, 0.0, Double.NEGATIVE_INFINITY, 1e300, -1e300, Double.MIN_VALUE, -Double.MIN_VALUE, Double.POSITIVE_INFINITY)
            values.forEach { db.execute("INSERT INTO t VALUES (?)", it) }
            assertEquals(values.sorted(), db.column("SELECT x FROM t"))
            db.fails("INSERT INTO t VALUES (?)", -0.0)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t WHERE x = ?", -0.0))
        }
    }

    @Test
    fun `blob keys sort as unsigned bytes`() {
        open().use { db ->
            db.execute("CREATE TABLE t (k BLOB PRIMARY KEY)")
            val keys = listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 1), byteArrayOf(1), byteArrayOf(-1), byteArrayOf(127), byteArrayOf(-128))
            keys.forEach { db.execute("INSERT INTO t VALUES (?)", it) }
            val expected = keys.map { it.toList() }.sortedWith { x, y ->
                java.util.Arrays.compareUnsigned(x.toByteArray(), y.toByteArray())
            }
            assertEquals(expected, db.column("SELECT k FROM t"))
        }
    }

    @Test
    fun `values larger than a page are stored in overflow pages`() {
        open().use { db ->
            val text = buildString { repeat(100_000) { append(('a' + it % 26)) } }
            val blob = ByteArray(50_000) { (it * 31).toByte() }
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT, b BLOB)")
            db.execute("INSERT INTO t VALUES (1, ?, ?)", text, blob)
            db.execute("INSERT INTO t VALUES (2, ?, NULL)", text.reversed())
            assertEquals(text, db.scalar("SELECT s FROM t WHERE id = 1"))
            assertEquals(blob.toList(), db.scalar("SELECT b FROM t WHERE id = 1"))
            assertEquals(100_000L, db.scalar("SELECT LENGTH(s) FROM t WHERE id = 2"))
            db.execute("DELETE FROM t")
            db.verify()
        }
    }

    @Test
    fun `oversized primary keys are rejected`() {
        open().use { db ->
            db.execute("CREATE TABLE t (name TEXT PRIMARY KEY)")
            val failure = db.fails("INSERT INTO t VALUES (?)", "x".repeat(200))
            assertTrue("primary key" in failure.message!!, failure.message)
            db.execute("INSERT INTO t VALUES (?)", "x".repeat(20))
            db.fails("UPDATE t SET name = ?", "y".repeat(200))
            assertEquals(listOf<Any?>("x".repeat(20)), db.column("SELECT name FROM t"))
        }
    }

    @Test
    fun `schema and data persist across reopen`() {
        open().use { db ->
            db.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
            db.execute("CREATE TABLE b (x INTEGER, y TEXT, PRIMARY KEY (y, x))")
            db.execute("INSERT INTO a (v) VALUES ('one'), ('two')")
            db.execute("INSERT INTO b VALUES (1, 'q'), (2, 'p')")
        }
        open().use { db ->
            assertEquals(listOf<Any?>("one", "two"), db.column("SELECT v FROM a"))
            assertEquals(listOf<Any?>(2L, 1L), db.column("SELECT x FROM b"))
            db.fails("INSERT INTO a (v) VALUES (NULL)")
            db.fails("INSERT INTO b VALUES (1, 'q')")
            assertEquals(3L, db.execute("INSERT INTO a (v) VALUES ('three')").lastInsertId)
        }
    }

    @Test
    fun `an exception inside a transaction rolls back every statement`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execute("INSERT INTO t VALUES (1, 'keep')")
            assertFailsWith<IllegalStateException> {
                db.transaction {
                    db.execute("INSERT INTO t VALUES (2, 'gone')")
                    db.execute("UPDATE t SET v = 'changed' WHERE id = 1")
                    db.execute("CREATE TABLE u (x INTEGER)")
                    error("boom")
                }
            }
            assertEquals(listOf(listOf<Any?>(1L, "keep")), db.rows("SELECT * FROM t"))
            db.fails("SELECT * FROM u")
        }
    }

    @Test
    fun `a failed statement inside a transaction aborts the whole transaction`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            assertFailsWith<DatabaseException> {
                db.transaction {
                    db.execute("INSERT INTO t VALUES (1)")
                    try {
                        db.execute("INSERT INTO t VALUES (1)")
                    } catch (_: SqlException) {
                        // swallowed on purpose: the transaction must still not commit
                    }
                }
            }
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `statements inside a transaction commit together`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
            var commits = 0
            db.pager.commitHook = { step, _ -> if (step == CommitStep.JOURNAL_INVALIDATED) commits++ }
            db.transaction {
                repeat(20) { db.execute("INSERT INTO t (v) VALUES (?)", it) }
                assertEquals(20L, db.scalar("SELECT COUNT(*) FROM t"))
            }
            assertEquals(1, commits)
            assertEquals(20L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `each statement outside a transaction is atomic`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 1), (2, 2), (3, 0)")
            db.fails("UPDATE t SET v = 10 / v")
            assertEquals(listOf<Any?>(1L, 2L, 0L), db.column("SELECT v FROM t"))
            db.fails("DELETE FROM t WHERE 10 / v > 1")
            assertEquals(3L, db.scalar("SELECT COUNT(*) FROM t"))
        }
    }

    @Test
    fun `sql tables share the file with plain buckets`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO t VALUES (1)")
            db.bucket("kv").put("k".toByteArray(), "v".toByteArray())
        }
        open().use { db ->
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM t"))
            assertEquals("v", db.bucket("kv").get("k".toByteArray())?.decodeToString())
        }
    }

    @Test
    fun `concurrent writers do not lose rows or reuse ids`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, who INTEGER)")
            val start = CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val workers = List(4) { who ->
                thread {
                    start.await()
                    try {
                        repeat(100) { db.execute("INSERT INTO t (who) VALUES (?)", who) }
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
            start.countDown()
            workers.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertEquals((1L..400L).toList(), db.column("SELECT id FROM t"))
            assertEquals(100L, db.scalar("SELECT COUNT(*) FROM t WHERE who = 2"))
        }
    }

    @Test
    fun `readers see whole statements only`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
            db.execute("INSERT INTO t VALUES (1, 0, 0)")
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val torn = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = thread {
                while (!stop.get()) {
                    val row = db.rows("SELECT a, b FROM t").single()
                    if (row[0] != row[1]) torn.set(true)
                }
            }
            repeat(300) { db.execute("UPDATE t SET a = a + 1, b = b + 1") }
            stop.set(true)
            reader.join()
            assertFalse(torn.get())
            assertEquals(300L, db.scalar("SELECT a FROM t"))
        }
    }

    @Test
    fun `a closed database refuses sql`() {
        val db = open()
        db.execute("CREATE TABLE t (id INTEGER)")
        db.close()
        assertFailsWith<IllegalStateException> { db.execute("SELECT * FROM t") }
    }

    @Test
    fun `read only results are independent of later changes`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO t VALUES (1)")
            val result = db.execute("SELECT * FROM t")
            db.execute("DELETE FROM t")
            assertEquals(1, result.rows.size)
            assertNull(db.rows("SELECT * FROM t").firstOrNull())
        }
    }
}
