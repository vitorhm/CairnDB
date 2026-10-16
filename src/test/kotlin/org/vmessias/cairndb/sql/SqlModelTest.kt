package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Database
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs random SQL statements against a table and a plain `TreeMap` side by side, reopening the file now and then. */
class SqlModelTest : SqlTestBase() {
    private class Rollback : RuntimeException()

    private data class Entry(val grp: Long, val txt: String?)

    private class Model {
        var rows = TreeMap<Long, Entry>()
        var counter = 1L

        fun snapshot() = TreeMap(rows) to counter

        fun restore(snapshot: Pair<TreeMap<Long, Entry>, Long>) {
            rows = TreeMap(snapshot.first)
            counter = snapshot.second
        }

        fun bump(id: Long) {
            if (id >= counter) counter = id + 1
        }
    }

    private class Runner(val random: Random, var db: Database, val model: Model)

    private val pieces = listOf("x", "y", "z", "\u00e9", "\uD83D\uDE00")

    private fun randomText(random: Random): String? = when (random.nextInt(20)) {
        0 -> null
        1 -> buildString { repeat(random.nextInt(900, 1600)) { append('a' + random.nextInt(26)) } }
        2, 3 -> buildString { repeat(random.nextInt(20, 300)) { append(pieces.random(random)) } }
        else -> buildString { repeat(random.nextInt(0, 20)) { append('a' + random.nextInt(26)) } }
    }

    private fun insertExplicit(r: Runner, safe: Boolean) {
        var id = r.random.nextLong(150, 250)
        if (safe) {
            var tries = 0
            while (r.model.rows.containsKey(id) && tries++ < 10) id = r.random.nextLong(150, 250)
            if (r.model.rows.containsKey(id)) return insertAuto(r)
        }
        val entry = Entry(r.random.nextLong(0, 5), randomText(r.random))
        if (r.model.rows.containsKey(id)) {
            r.db.fails("INSERT INTO kv VALUES (?, ?, ?)", id, entry.grp, entry.txt)
            return
        }
        val result = r.db.execute("INSERT INTO kv VALUES (?, ?, ?)", id, entry.grp, entry.txt)
        assertEquals(1, result.rowsAffected)
        assertEquals(id, result.lastInsertId)
        r.model.rows[id] = entry
        r.model.bump(id)
    }

    private fun insertAuto(r: Runner) {
        val count = r.random.nextInt(1, 4)
        val entries = List(count) { Entry(r.random.nextLong(0, 5), randomText(r.random)) }
        val placeholders = List(count) { "(?, ?)" }.joinToString(", ")
        val result = r.db.execute("INSERT INTO kv (grp, txt) VALUES $placeholders", *entries.flatMap { listOf<Any?>(it.grp, it.txt) }.toTypedArray())
        assertEquals(count, result.rowsAffected)
        for (entry in entries) {
            r.model.rows[r.model.counter] = entry
            r.model.counter++
        }
        assertEquals(r.model.counter - 1, result.lastInsertId)
    }

    private fun updateGroup(r: Runner) {
        val low = r.random.nextLong(140, 260)
        val high = low + r.random.nextLong(0, 40)
        val delta = r.random.nextLong(-2, 3)
        val affected = r.model.rows.subMap(low, true, high, true)
        val expected = affected.size
        affected.replaceAll { _, entry -> entry.copy(grp = entry.grp + delta) }
        assertEquals(expected, r.db.update("UPDATE kv SET grp = grp + ? WHERE id BETWEEN ? AND ?", delta, low, high))
    }

    private fun updateText(r: Runner) {
        if (r.random.nextBoolean()) {
            val grp = r.random.nextLong(-2, 7)
            val text = randomText(r.random)
            var expected = 0
            r.model.rows.replaceAll { _, entry -> if (entry.grp == grp) entry.copy(txt = text).also { expected++ } else entry }
            assertEquals(expected, r.db.update("UPDATE kv SET txt = ? WHERE grp = ?", text, grp))
        } else {
            val low = r.random.nextLong(140, 260)
            val high = low + r.random.nextLong(0, 40)
            val suffix = "-" + r.random.nextInt(100)
            val affected = r.model.rows.subMap(low, true, high, false)
            val expected = affected.size
            affected.replaceAll { _, entry -> entry.copy(txt = entry.txt?.plus(suffix)) }
            assertEquals(expected, r.db.update("UPDATE kv SET txt = txt || ? WHERE id >= ? AND id < ?", suffix, low, high))
        }
    }

    private fun delete(r: Runner) {
        when (r.random.nextInt(3)) {
            0 -> {
                val low = r.random.nextLong(140, 260)
                val high = low + r.random.nextLong(0, 30)
                val affected = r.model.rows.subMap(low, true, high, false)
                val expected = affected.size
                affected.clear()
                assertEquals(expected, r.db.update("DELETE FROM kv WHERE id >= ? AND id < ?", low, high))
            }
            1 -> {
                val grp = r.random.nextLong(-2, 7)
                val expected = r.model.rows.values.count { it.grp == grp }
                r.model.rows.values.removeIf { it.grp == grp }
                assertEquals(expected, r.db.update("DELETE FROM kv WHERE grp = ?", grp))
            }
            else -> {
                val id = r.random.nextLong(1, 250)
                val expected = if (r.model.rows.remove(id) != null) 1 else 0
                assertEquals(expected, r.db.update("DELETE FROM kv WHERE id = ?", id))
            }
        }
    }

    private fun rekey(r: Runner, safe: Boolean) {
        val source = r.random.nextLong(150, 250)
        var target = r.random.nextLong(150, 250)
        if (safe) {
            var tries = 0
            while (target != source && r.model.rows.containsKey(target) && tries++ < 10) target = r.random.nextLong(150, 250)
            if (target != source && r.model.rows.containsKey(target)) return
        }
        val entry = r.model.rows[source]
        if (entry != null && target != source && r.model.rows.containsKey(target)) {
            r.db.fails("UPDATE kv SET id = ? WHERE id = ?", target, source)
            return
        }
        val expected = if (entry != null) 1 else 0
        assertEquals(expected, r.db.update("UPDATE kv SET id = ? WHERE id = ?", target, source))
        if (entry != null) {
            r.model.rows.remove(source)
            r.model.rows[target] = entry
            r.model.bump(target)
        }
    }

    private fun shift(r: Runner) {
        val from = r.random.nextLong(100, 260)
        val tail = r.model.rows.tailMap(from, true)
        val moved = TreeMap(tail)
        tail.clear()
        moved.forEach { (id, entry) -> r.model.rows[id + 1] = entry }
        moved.lastEntry()?.let { r.model.bump(it.key + 1) }
        assertEquals(moved.size, r.db.update("UPDATE kv SET id = id + 1 WHERE id >= ?", from))
    }

    private fun reflect(r: Runner) {
        val spread = r.random.nextLong(0, 25)
        val centre = 200L
        val window = r.model.rows.subMap(centre - spread, true, centre + spread, true)
        val moved = TreeMap(window)
        window.clear()
        moved.forEach { (id, entry) -> r.model.rows[2 * centre - id] = entry; r.model.bump(2 * centre - id) }
        assertEquals(moved.size, r.db.update("UPDATE kv SET id = 400 - id WHERE id BETWEEN ? AND ?", centre - spread, centre + spread))
    }

    private fun step(r: Runner, safe: Boolean) {
        when (r.random.nextInt(14)) {
            0, 1, 2 -> insertExplicit(r, safe)
            3, 4, 5 -> insertAuto(r)
            6 -> updateGroup(r)
            7 -> updateText(r)
            8, 9 -> delete(r)
            10, 11 -> rekey(r, safe)
            12 -> shift(r)
            else -> reflect(r)
        }
    }

    private fun check(r: Runner) {
        val expected = r.model.rows.map { (id, entry) -> listOf<Any?>(id, entry.grp, entry.txt) }
        val actual = r.db.rows("SELECT id, grp, txt FROM kv")
        val firstDifference = (0 until maxOf(expected.size, actual.size)).firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) }
        assertEquals(null, firstDifference, "first difference: expected ${expected.getOrNull(firstDifference ?: 0)?.map { it.toString().take(30) }} but was ${actual.getOrNull(firstDifference ?: 0)?.map { it.toString().take(30) }}; sizes ${expected.size}/${actual.size}")

        val low = r.random.nextLong(100, 300)
        val high = low + r.random.nextLong(0, 80)
        assertEquals(
            r.model.rows.subMap(low, false, high, true).keys.toList(),
            r.db.column("SELECT id FROM kv WHERE id > ? AND id <= ?", low, high),
        )
        assertEquals(
            r.model.rows.headMap(low, false).keys.toList().asReversed().take(3),
            r.db.column("SELECT id FROM kv WHERE id < ? ORDER BY id DESC LIMIT 3", low),
        )
        assertEquals(r.model.rows.size.toLong(), r.db.scalar("SELECT COUNT(*) FROM kv"))
        val groups = r.model.rows.values.groupingBy { it.grp }.eachCount().toSortedMap().map { listOf<Any?>(it.key, it.value.toLong()) }
        assertEquals(groups, r.db.rows("SELECT grp, COUNT(*) FROM kv GROUP BY grp ORDER BY grp"))
        val sum = r.model.rows.values.sumOf { it.grp }
        assertEquals(if (r.model.rows.isEmpty()) null else sum, r.db.scalar("SELECT SUM(grp) FROM kv"))
    }

    private fun run(seed: Int, steps: Int) {
        val random = Random(seed)
        val runner = Runner(random, open(), Model())
        runner.db.execute("CREATE TABLE kv (id INTEGER PRIMARY KEY, grp INTEGER NOT NULL, txt TEXT)")
        repeat(steps) { n ->
            if (random.nextInt(40) == 0) {
                val before = runner.model.snapshot()
                val keep = random.nextBoolean()
                val body = {
                    runner.db.transaction {
                        repeat(random.nextInt(1, 6)) { step(runner, safe = true) }
                        if (!keep) throw Rollback()
                    }
                }
                if (keep) {
                    body()
                } else {
                    assertFailsWith<Rollback> { body() }
                    runner.model.restore(before)
                }
            } else {
                step(runner, safe = false)
            }
            if (n % 15 == 0) check(runner)
            if (random.nextInt(120) == 0) {
                runner.db.verify()
                runner.db.close()
                runner.db = open()
                check(runner)
            }
        }
        check(runner)
        runner.db.verify()
        runner.db.close()
        open().use { db ->
            assertEquals(runner.model.rows.size.toLong(), db.scalar("SELECT COUNT(*) FROM kv"))
            val inserted = db.execute("INSERT INTO kv (grp) VALUES (0)").lastInsertId
            assertEquals(runner.model.counter, inserted)
            db.verify()
        }
    }

    @Test
    fun `random statements match the model - seed 1`() = run(1, 1500)

    @Test
    fun `random statements match the model - seed 2`() = run(2, 1500)

    @Test
    fun `random statements match the model - seed 3`() = run(3, 1500)
}
