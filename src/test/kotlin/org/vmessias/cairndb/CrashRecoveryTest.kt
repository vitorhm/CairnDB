package org.vmessias.cairndb

import org.vmessias.cairndb.storage.CommitStep
import org.vmessias.cairndb.storage.Pager
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash-safety tests. The pager reports every step of a commit; the tests copy the database and
 * journal files at each step (what a crash at that instant would leave behind) and check that
 * opening the copy yields either the state before the transaction or the state after it, never a mix.
 */
class CrashRecoveryTest : DatabaseTestBase() {
    private val options = DatabaseOptions(pageSize = 512, syncOnCommit = false)

    private class State(val data: Map<String, String>, val buckets: List<String>)

    private class Snapshot(val step: CommitStep, val pageId: Int, val file: Path)

    private val before = State(
        data = (0 until 300).associate { "key-%04d".format(it) to "value-$it-" + "x".repeat(it % 40) } + ("big" to "B".repeat(3000)),
        buckets = listOf("data"),
    )

    private fun populate(db: Database) {
        val bucket = db.bucket("data")
        before.data.forEach { (k, v) -> bucket.put(k, v) }
    }

    /** One transaction that splits, merges, overflows, creates a bucket and replaces values. */
    private fun mutate(db: Database): State {
        val expected = before.data.toMutableMap()
        db.transaction {
            val bucket = db.bucket("data")
            for (i in 0 until 300 step 3) {
                val k = "key-%04d".format(i)
                bucket.delete(k)
                expected.remove(k)
            }
            for (i in 1 until 300 step 5) {
                val k = "key-%04d".format(i)
                bucket.put(k, "changed-$i")
                expected[k] = "changed-$i"
            }
            for (i in 300 until 420) {
                val k = "key-%04d".format(i)
                bucket.put(k, "new-$i")
                expected[k] = "new-$i"
            }
            bucket.put("big", "C".repeat(5000))
            expected["big"] = "C".repeat(5000)
            db.bucket("extra").put("e", "1")
        }
        return State(expected, listOf("data", "extra"))
    }

    private fun readState(db: Database): State {
        val data = db.bucket("data").scan { entries -> entries.associate { it.key.decodeToString() to it.value.decodeToString() } }
        return State(data.toSortedMap(), db.bucketNames())
    }

    private fun assertState(expected: State, actual: State, message: String) {
        assertEquals(expected.buckets, actual.buckets, message)
        assertEquals(expected.data.toSortedMap(), actual.data.toSortedMap(), message)
    }

    private fun snapshots(db: Database, action: () -> Unit): List<Snapshot> {
        val taken = mutableListOf<Snapshot>()
        db.pager.commitHook = { step, pageId ->
            val directory = Files.createDirectories(dir.resolve("snap-${taken.size}"))
            val copy = directory.resolve("test.db")
            Files.copy(file, copy)
            val journal = Pager.journalPath(file)
            if (Files.exists(journal)) Files.copy(journal, Pager.journalPath(copy))
            if (step == CommitStep.PAGE_WRITTEN) tear(copy, pageId)
            taken += Snapshot(step, pageId, copy)
        }
        try {
            action()
        } finally {
            db.pager.commitHook = null
        }
        return taken
    }

    /** Simulates a torn write: only the first half of the page that was just written is sane. */
    private fun tear(copy: Path, pageId: Int) {
        RandomAccessFile(copy.toFile(), "rw").use {
            it.seek(pageId * 512L + 256)
            it.write(ByteArray(256) { 0x5A })
        }
    }

    @Test
    fun `a crash at any step of a commit leaves the old or the new state`() {
        val db = open(options)
        populate(db)
        val sizeBefore = Files.size(file)
        lateinit var after: State
        val taken = snapshots(db) { after = mutate(db) }

        assertEquals(CommitStep.JOURNAL_RECORDS_WRITTEN, taken.first().step)
        assertEquals(CommitStep.JOURNAL_INVALIDATED, taken.last().step)
        assertTrue(taken.count { it.step == CommitStep.PAGE_WRITTEN } > 10, "the transaction should touch many pages")

        taken.forEachIndexed { i, snapshot ->
            val expected = if (snapshot.step == CommitStep.JOURNAL_INVALIDATED) after else before
            val label = "snapshot $i (${snapshot.step}, page ${snapshot.pageId})"
            Database.open(snapshot.file, options).use { recovered ->
                assertState(expected, readState(recovered), label)
                recovered.verify()
            }
            if (snapshot.step != CommitStep.JOURNAL_INVALIDATED) {
                assertEquals(sizeBefore, Files.size(snapshot.file), "$label: file must shrink back")
            }
        }
    }

    @Test
    fun `a failed commit is undone at once and the database stays usable`() {
        val scenarios = listOf(
            CommitStep.JOURNAL_RECORDS_WRITTEN to 1,
            CommitStep.JOURNAL_HEADER_WRITTEN to 1,
            CommitStep.PAGE_WRITTEN to 1,
            CommitStep.PAGE_WRITTEN to 6,
            CommitStep.DATABASE_SYNCED to 1,
        )
        for ((failAt, occurrence) in scenarios) {
            val label = "$failAt #$occurrence"
            Files.deleteIfExists(file)
            Files.deleteIfExists(Pager.journalPath(file))
            Database.open(file, options).use { db ->
                populate(db)
                val sizeBefore = Files.size(file)
                var seen = 0
                db.pager.commitHook = { step, _ ->
                    if (step == failAt && ++seen == occurrence) throw IOException("injected failure")
                }
                assertFailsWith<IOException>(label) { mutate(db) }
                db.pager.commitHook = null

                assertState(before, readState(db), label)
                db.verify()
                assertEquals(sizeBefore, Files.size(file), "$label: file size")

                db.bucket("data").put("after", "ok")
                db.verify()
            }
            Database.open(file, options).use { db ->
                val state = readState(db)
                assertEquals("ok", state.data["after"], label)
                assertEquals(before.data.size + 1, state.data.size, label)
                db.verify()
            }
        }
    }

    @Test
    fun `a hot journal that is damaged is reported and the database is left alone`() {
        val db = open(options)
        populate(db)
        val taken = snapshots(db) { mutate(db) }
        val hot = taken.first { it.step == CommitStep.DATABASE_SYNCED }
        val journal = Pager.journalPath(hot.file)
        val damaged = Files.readAllBytes(journal).also { it[32 + 10] = (it[32 + 10] + 1).toByte() }
        Files.write(journal, damaged)
        val databaseBefore = Files.readAllBytes(hot.file)

        assertFailsWith<CorruptDatabaseException> { Database.open(hot.file, options) }
        assertContentEquals(databaseBefore, Files.readAllBytes(hot.file))
        assertContentEquals(damaged, Files.readAllBytes(journal), "the evidence must be kept")
    }

    @Test
    fun `a journal that never became hot is ignored`() {
        val db = open(options)
        populate(db)
        val taken = snapshots(db) { mutate(db) }
        val incomplete = taken.first { it.step == CommitStep.JOURNAL_RECORDS_WRITTEN }
        assertTrue(Files.size(Pager.journalPath(incomplete.file)) > 0)
        Database.open(incomplete.file, options).use { assertState(before, readState(it), "incomplete journal") }

        val garbage = dir.resolve("garbage.db")
        Files.copy(incomplete.file, garbage)
        Files.write(Pager.journalPath(garbage), ByteArray(100) { it.toByte() })
        Database.open(garbage, options).use { assertState(before, readState(it), "garbage journal") }
    }

    @Test
    fun `the journal file does not outlive a clean close`() {
        open(options).use { it.bucket("b").put("k", "v") }
        assertFalse(Files.exists(Pager.journalPath(file)))
        Files.createFile(Pager.journalPath(file))
        open(options).use { assertEquals("v", it.bucket("b").get("k")) }
        assertFalse(Files.exists(Pager.journalPath(file)))
    }

    @Test
    fun `commit steps happen in the documented order`() {
        val db = open(options)
        val bucket = db.bucket("b")
        val steps = mutableListOf<CommitStep>()
        db.pager.commitHook = { step, _ -> steps += step }
        bucket.put("k", "v")
        assertEquals(
            listOf(CommitStep.JOURNAL_RECORDS_WRITTEN, CommitStep.JOURNAL_HEADER_WRITTEN),
            steps.take(2),
        )
        assertEquals(
            listOf(CommitStep.DATABASE_SYNCED, CommitStep.JOURNAL_INVALIDATED),
            steps.takeLast(2),
        )
        assertTrue(steps.drop(2).dropLast(2).all { it == CommitStep.PAGE_WRITTEN })
    }
}
