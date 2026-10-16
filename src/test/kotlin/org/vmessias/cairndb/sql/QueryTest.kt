package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryTest : SqlTestBase() {
    private fun people(): Database = open().also { db ->
        db.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT, age INTEGER, city TEXT, score REAL)")
        db.execute(
            """INSERT INTO people VALUES
               (1, 'Alice', 30, 'Lisbon', 9.5),
               (2, 'bob', 25, 'Porto', 7.0),
               (3, 'Carol', NULL, 'Lisbon', NULL),
               (4, 'dave', 25, NULL, 8.25),
               (5, 'Eve', 41, 'Porto', 6.0),
               (6, 'Frank', 30, 'Faro', NULL),
               (7, 'Alice', 19, 'Faro', 5.5)""",
        )
    }

    private fun ids(db: Database, sql: String, vararg params: Any?): List<Any?> = db.rows(sql, *params).map { it[0] }

    private fun longs(vararg values: Long): List<Any?> = values.toList()

    @Test
    fun `where combines predicates`() {
        people().use { db ->
            assertEquals(longs(1, 6), ids(db, "SELECT id FROM people WHERE age = 30"))
            assertEquals(longs(1), ids(db, "SELECT id FROM people WHERE age = 30 AND city = 'Lisbon'"))
            assertEquals(longs(1, 2, 4, 6), ids(db, "SELECT id FROM people WHERE age = 30 OR age = 25"))
            assertEquals(longs(3, 4), ids(db, "SELECT id FROM people WHERE age IS NULL OR city IS NULL"))
            assertEquals(longs(2, 4, 5, 7), ids(db, "SELECT id FROM people WHERE NOT age = 30 AND age IS NOT NULL"))
            assertEquals(longs(1, 6, 7), ids(db, "SELECT id FROM people WHERE age IN (19, 30)"))
            assertEquals(longs(2, 4, 5), ids(db, "SELECT id FROM people WHERE age BETWEEN 20 AND 50 AND age <> 30"))
            assertEquals(longs(1, 3, 7), ids(db, "SELECT id FROM people WHERE name LIKE 'a%' OR name LIKE 'C%'"))
            assertEquals(longs(2, 4, 5), ids(db, "SELECT id FROM people WHERE name LIKE '___' OR name LIKE '____'"))
        }
    }

    @Test
    fun `comparisons with null never match`() {
        people().use { db ->
            assertEquals(emptyList(), ids(db, "SELECT id FROM people WHERE age = NULL"))
            assertEquals(emptyList(), ids(db, "SELECT id FROM people WHERE age <> NULL"))
            assertEquals(longs(1, 2, 4, 5, 6, 7), ids(db, "SELECT id FROM people WHERE age > 0"))
            assertEquals(longs(2, 4, 7), ids(db, "SELECT id FROM people WHERE NOT (age > 25)"))
        }
    }

    @Test
    fun `select list expressions and aliases`() {
        people().use { db ->
            val result = db.execute("SELECT id, age * 2 AS double_age, UPPER(name) || '!' AS shout, score FROM people WHERE id IN (1, 3)")
            assertEquals(listOf("id", "double_age", "shout", "score"), result.columns)
            assertEquals(
                listOf(listOf<Any?>(1L, 60L, "ALICE!", 9.5), listOf<Any?>(3L, null, "CAROL!", null)),
                result.rows.map { it.values },
            )
            assertEquals(listOf(listOf<Any?>(1L, "Alice", 30L, "Lisbon", 9.5, 1L)), db.rows("SELECT *, 1 FROM people WHERE id = 1"))
            assertEquals(listOf<Any?>("Alice"), db.column("SELECT people.name FROM people WHERE id = 1"))
        }
    }

    @Test
    fun `order by direction and null placement`() {
        people().use { db ->
            assertEquals(longs(3, 7, 2, 4, 1, 6, 5), ids(db, "SELECT id FROM people ORDER BY age, id"))
            assertEquals(longs(5, 1, 6, 2, 4, 7, 3), ids(db, "SELECT id FROM people ORDER BY age DESC, id"))
            assertEquals(longs(3, 7, 2, 4, 1, 6, 5), ids(db, "SELECT id FROM people ORDER BY age"), "sorting is stable")
            assertEquals(longs(4, 6, 7, 1, 3, 5, 2), ids(db, "SELECT id FROM people ORDER BY city, age DESC, id"))
            assertEquals(longs(1, 4, 2, 5, 6), ids(db, "SELECT id FROM people WHERE age >= 25 ORDER BY score DESC, id"))
        }
    }

    @Test
    fun `order by text is by code point so upper case comes first`() {
        people().use { db ->
            assertEquals(
                listOf<Any?>("Alice", "Alice", "Carol", "Eve", "Frank", "bob", "dave"),
                db.column("SELECT name FROM people ORDER BY name"),
            )
            assertEquals(longs(1, 7, 3, 5, 6, 2, 4), ids(db, "SELECT id FROM people ORDER BY name, id"))
        }
    }

    @Test
    fun `order by alias ordinal expression and unselected column`() {
        people().use { db ->
            assertEquals(longs(3, 7, 2, 4, 1, 6, 5), ids(db, "SELECT id, age AS a FROM people ORDER BY a, id"))
            assertEquals(longs(7, 6, 5, 4, 3, 2, 1), ids(db, "SELECT id AS n FROM people ORDER BY n DESC"))
            assertEquals(listOf<Any?>("Alice", "Alice", "Carol", "Eve", "Frank", "bob", "dave"), db.column("SELECT name FROM people ORDER BY 1"))
            assertEquals(longs(2, 5, 4, 1, 3, 6, 7), ids(db, "SELECT id FROM people ORDER BY LENGTH(name), id"))
            assertEquals(longs(7, 2, 4, 1, 6, 5, 3), ids(db, "SELECT id FROM people ORDER BY -age DESC, id"))
            db.fails("SELECT id FROM people ORDER BY 9")
            db.fails("SELECT id FROM people ORDER BY 0")
            db.fails("SELECT id FROM people ORDER BY nope")
        }
    }

    @Test
    fun `limit and offset`() {
        people().use { db ->
            assertEquals(longs(1, 2, 3), ids(db, "SELECT id FROM people LIMIT 3"))
            assertEquals(longs(3, 4, 5), ids(db, "SELECT id FROM people LIMIT 3 OFFSET 2"))
            assertEquals(longs(6, 7), ids(db, "SELECT id FROM people LIMIT 10 OFFSET 5"))
            assertEquals(emptyList(), ids(db, "SELECT id FROM people LIMIT 0"))
            assertEquals(emptyList(), ids(db, "SELECT id FROM people LIMIT 5 OFFSET 10"))
            assertEquals(longs(5, 1), ids(db, "SELECT id FROM people ORDER BY age DESC, id LIMIT 2"))
            assertEquals(longs(3, 4), ids(db, "SELECT id FROM people LIMIT ? OFFSET ?", 2, 2))
            assertEquals(longs(1, 2), ids(db, "SELECT id FROM people LIMIT 1 + 1"))
            assertEquals(emptyList(), ids(db, "SELECT id FROM people LIMIT 9223372036854775807 OFFSET 9223372036854775807"))
            assertEquals(7, db.rows("SELECT id FROM people LIMIT 9223372036854775807").size)
            db.fails("SELECT id FROM people LIMIT -1")
            db.fails("SELECT id FROM people LIMIT 1.5")
            db.fails("SELECT id FROM people LIMIT 'a'")
            db.fails("SELECT id FROM people LIMIT age")
            db.fails("SELECT id FROM people LIMIT 1 OFFSET -1")
        }
    }

    @Test
    fun `distinct removes duplicates including nulls`() {
        people().use { db ->
            assertEquals(listOf<Any?>(null, 19L, 25L, 30L, 41L), db.column("SELECT DISTINCT age FROM people ORDER BY age"))
            assertEquals(listOf<Any?>(null, "Faro", "Lisbon", "Porto"), db.column("SELECT DISTINCT city FROM people ORDER BY city"))
            assertEquals(listOf<Any?>("Alice", "bob", "Carol", "dave", "Eve", "Frank"), db.column("SELECT DISTINCT name FROM people"))
            assertEquals(7, db.rows("SELECT DISTINCT name, age FROM people").size)
            assertEquals(listOf<Any?>(30L, 25L), db.column("SELECT DISTINCT age FROM people LIMIT 2"))
            assertEquals(6, db.rows("SELECT DISTINCT score FROM people").size)
        }
    }

    @Test
    fun `aggregates over a whole table`() {
        people().use { db ->
            val row = db.rows("SELECT COUNT(*), COUNT(age), COUNT(city), SUM(age), MIN(age), MAX(age), AVG(age) FROM people").single()
            assertEquals(listOf<Any?>(7L, 6L, 6L, 170L, 19L, 41L, 170.0 / 6), row)
            assertEquals(
                listOf<Any?>(36.25, 5.5, 9.5, "Alice", "dave"),
                db.rows("SELECT SUM(score), MIN(score), MAX(score), MIN(name), MAX(name) FROM people").single(),
            )
            assertEquals(5L, db.scalar("SELECT COUNT(*) FROM people WHERE age > 20"))
            assertEquals(listOf<Any?>(22L, 14L), db.rows("SELECT MAX(age) - MIN(age), COUNT(*) * 2 FROM people").single())
            assertEquals(11L, db.scalar("SELECT COUNT(age + 1) + COUNT(*) - 2 FROM people"))
            assertEquals("real", db.scalar("SELECT TYPEOF(AVG(age)) FROM people"))
            assertEquals("integer", db.scalar("SELECT TYPEOF(SUM(age)) FROM people"))
        }
    }

    @Test
    fun `aggregates over no rows`() {
        people().use { db ->
            assertEquals(
                listOf<Any?>(0L, 0L, null, null, null, null),
                db.rows("SELECT COUNT(*), COUNT(age), SUM(age), MIN(age), AVG(age), MAX(name) FROM people WHERE 1 = 0").single(),
            )
            assertEquals(emptyList(), db.rows("SELECT city, COUNT(*) FROM people WHERE 1 = 0 GROUP BY city"))
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM people WHERE id > 100"))
        }
    }

    @Test
    fun `aggregate errors`() {
        people().use { db ->
            db.execute("CREATE TABLE big (v INTEGER)")
            db.execute("INSERT INTO big VALUES (?), (1)", Long.MAX_VALUE)
            db.fails("SELECT SUM(v) FROM big")
            db.fails("SELECT SUM(name) FROM people")
            db.fails("SELECT AVG(name) FROM people")
            db.fails("SELECT name, COUNT(*) FROM people")
            db.fails("SELECT COUNT(*) FROM people WHERE COUNT(*) > 1")
            db.fails("SELECT SUM(COUNT(*)) FROM people")
            db.fails("SELECT COUNT(*) FROM people GROUP BY COUNT(*)")
            db.fails("SELECT COUNT(1, 2) FROM people")
            assertEquals(Long.MAX_VALUE, db.scalar("SELECT MAX(v) FROM big"))
        }
    }

    @Test
    fun `group by`() {
        people().use { db ->
            assertEquals(
                listOf(listOf<Any?>(null, 1L), listOf<Any?>("Faro", 2L), listOf<Any?>("Lisbon", 2L), listOf<Any?>("Porto", 2L)),
                db.rows("SELECT city, COUNT(*) FROM people GROUP BY city ORDER BY city"),
            )
            assertEquals(
                listOf(listOf<Any?>("Faro", 2L, 24.5), listOf<Any?>("Lisbon", 2L, 30.0), listOf<Any?>("Porto", 2L, 33.0)),
                db.rows("SELECT city, COUNT(*) AS n, AVG(age) FROM people GROUP BY city HAVING COUNT(*) > 1 ORDER BY city"),
            )
            assertEquals(
                listOf(listOf<Any?>(25L, 2L), listOf<Any?>(30L, 2L), listOf<Any?>(null, 1L), listOf<Any?>(19L, 1L), listOf<Any?>(41L, 1L)),
                db.rows("SELECT age, COUNT(*) FROM people GROUP BY age ORDER BY COUNT(*) DESC, age"),
            )
            assertEquals(
                listOf(listOf<Any?>(null, 1L), listOf<Any?>(1L, 1L), listOf<Any?>(2L, 2L), listOf<Any?>(3L, 2L), listOf<Any?>(4L, 1L)),
                db.rows("SELECT age / 10 AS decade, COUNT(*) FROM people GROUP BY age / 10 ORDER BY decade"),
            )
            assertEquals(
                listOf(listOf<Any?>(null, 50L), listOf<Any?>("Faro", 98L), listOf<Any?>("Lisbon", 60L), listOf<Any?>("Porto", 132L)),
                db.rows("SELECT city, SUM(age * 2) FROM people GROUP BY city ORDER BY city"),
            )
        }
    }

    @Test
    fun `group by accepts ordinals aliases and expressions over group columns`() {
        people().use { db ->
            val expected = listOf(listOf<Any?>(null, 1L), listOf<Any?>("FARO", 2L), listOf<Any?>("LISBON", 2L), listOf<Any?>("PORTO", 2L))
            assertEquals(expected, db.rows("SELECT UPPER(city) AS c, COUNT(*) FROM people GROUP BY c ORDER BY c"))
            assertEquals(expected, db.rows("SELECT UPPER(city), COUNT(*) FROM people GROUP BY 1 ORDER BY 1"))
            assertEquals(expected, db.rows("SELECT UPPER(city), COUNT(*) FROM people GROUP BY UPPER(city) ORDER BY UPPER(city)"))
            assertEquals(
                listOf(listOf<Any?>("Faro", "Faro!")),
                db.rows("SELECT city, city || '!' FROM people GROUP BY city HAVING city = 'Faro'"),
            )
        }
    }

    @Test
    fun `null values form a single group`() {
        people().use { db ->
            db.execute("INSERT INTO people VALUES (8, 'Gus', NULL, NULL, NULL)")
            assertEquals(
                listOf(listOf<Any?>(null, 2L), listOf<Any?>("Faro", 2L), listOf<Any?>("Lisbon", 2L), listOf<Any?>("Porto", 2L)),
                db.rows("SELECT city, COUNT(*) FROM people GROUP BY city ORDER BY city"),
            )
        }
    }

    @Test
    fun `group by with distinct and limit`() {
        people().use { db ->
            assertEquals(listOf<Any?>(1L, 2L), db.column("SELECT DISTINCT COUNT(*) AS n FROM people GROUP BY city ORDER BY n"))
            assertEquals(
                listOf(listOf<Any?>(null, 1L), listOf<Any?>("Faro", 2L)),
                db.rows("SELECT city, COUNT(*) FROM people GROUP BY city ORDER BY city LIMIT 2"),
            )
        }
    }

    @Test
    fun `grouping errors`() {
        people().use { db ->
            val failure = db.fails("SELECT name, COUNT(*) FROM people GROUP BY city")
            assertTrue("GROUP BY" in failure.message!!, failure.message)
            db.fails("SELECT COUNT(*) FROM people GROUP BY nope")
            db.fails("SELECT city FROM people GROUP BY city HAVING age > 1")
            db.fails("SELECT * FROM people GROUP BY city")
            db.fails("SELECT city FROM people GROUP BY 5")
            db.fails("SELECT city FROM people GROUP BY city ORDER BY age")
        }
    }

    @Test
    fun `column and table qualifiers are checked`() {
        people().use { db ->
            db.fails("SELECT people2.name FROM people")
            db.fails("SELECT nope FROM people")
            db.fails("SELECT * FROM people WHERE nope = 1")
            db.fails("SELECT * FROM people WHERE people.nope = 1")
            assertEquals(longs(1), ids(db, "SELECT people.id FROM people WHERE people.id = 1"))
        }
    }

    @Test
    fun `type errors surface only for the rows that are evaluated`() {
        people().use { db ->
            db.fails("SELECT id FROM people WHERE name > 5")
            db.fails("SELECT id FROM people WHERE age = 'x'")
            assertEquals(emptyList(), ids(db, "SELECT id FROM people WHERE 1 = 0 AND name > 5"))
            db.execute("CREATE TABLE empty (a TEXT)")
            assertEquals(emptyList(), ids(db, "SELECT a FROM empty WHERE a > 5"))
        }
    }

    @Test
    fun `numbers that compare equal share a group and a distinct slot`() {
        open().use { db ->
            db.execute("CREATE TABLE m (id INTEGER PRIMARY KEY, i INTEGER, r REAL)")
            db.execute("INSERT INTO m VALUES (1, 1, NULL), (2, NULL, 1.0), (3, 2, NULL), (4, NULL, 2.5), (5, NULL, -0.0), (6, 0, NULL)")
            assertEquals(4, db.rows("SELECT DISTINCT COALESCE(i, r) FROM m").size)
            assertEquals(
                listOf(listOf<Any?>(2L), listOf<Any?>(2L), listOf<Any?>(1L), listOf<Any?>(1L)),
                db.rows("SELECT COUNT(*) FROM m GROUP BY COALESCE(i, r) ORDER BY COALESCE(i, r)"),
            )
        }
    }

    @Test
    fun `reading while a transaction is open sees its own writes`() {
        open().use { db ->
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
            db.transaction {
                db.execute("INSERT INTO t VALUES (1, 10), (2, 20)")
                assertEquals(30L, db.scalar("SELECT SUM(v) FROM t"))
                db.execute("DELETE FROM t WHERE id = 1")
                assertEquals(20L, db.scalar("SELECT SUM(v) FROM t"))
            }
            assertEquals(20L, db.scalar("SELECT SUM(v) FROM t"))
        }
    }
}
