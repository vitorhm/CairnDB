package org.vmessias.cairndb

import org.vmessias.cairndb.storage.CommitStep
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionTest : DatabaseTestBase() {
    private fun commits(db: Database): () -> Int {
        var count = 0
        db.pager.commitHook = { step, _ -> if (step == CommitStep.JOURNAL_INVALIDATED) count++ }
        return { count }
    }

    @Test
    fun `a transaction is a single commit across buckets and survives reopening`() {
        val db = open()
        val users = db.bucket("users")
        val orders = db.bucket("orders")
        val commitCount = commits(db)

        val result = db.transaction {
            repeat(100) { users.put(key(it), "user $it".toByteArray()) }
            repeat(100) { orders.put(key(it), "order $it".toByteArray()) }
            "done"
        }

        assertEquals("done", result)
        assertEquals(1, commitCount())
        db.close()
        open().use {
            assertEquals(100, it.bucket("users").count())
            assertEquals("order 42", it.bucket("orders").get(key(42))?.decodeToString())
            it.verify()
        }
    }

    @Test
    fun `plain operations still commit one by one`() {
        val db = open()
        val bucket = db.bucket("b")
        val commitCount = commits(db)
        repeat(5) { bucket.put(key(it), byteArrayOf(1)) }
        assertEquals(5, commitCount())
    }

    @Test
    fun `an exception rolls back every change including created and dropped buckets`() {
        val db = open()
        val keep = db.bucket("keep")
        repeat(300) { keep.put(key(it), ByteArray(40)) }
        db.bucket("doomed").put("k", "v")
        val sizeBefore = Files.size(file)

        assertFailsWith<IllegalArgumentException>("boom") {
            db.transaction {
                repeat(300) { keep.delete(key(it)) }
                repeat(500) { keep.put(key(1000 + it), ByteArray(200)) }
                db.bucket("fresh").put("x", "y")
                db.dropBucket("doomed")
                throw IllegalArgumentException("boom")
            }
        }

        assertEquals(listOf("doomed", "keep"), db.bucketNames())
        assertEquals(300, keep.count())
        assertEquals("v", db.bucket("doomed").get("k"))
        assertEquals(sizeBefore, Files.size(file))
        db.verify()

        db.bucket("fresh").put("x", "after")
        assertEquals("after", db.bucket("fresh").get("x"))
    }

    @Test
    fun `reads inside a transaction see its uncommitted writes`() {
        val db = open()
        val bucket = db.bucket("b")
        bucket.put("a", "old")
        db.transaction {
            bucket.put("a", "new")
            bucket.put("b", "added")
            assertEquals("new", bucket.get("a"))
            assertEquals(2, bucket.count())
            bucket.delete("a")
            assertNull(bucket.get("a"))
        }
        assertNull(bucket.get("a"))
        assertEquals("added", bucket.get("b"))
    }

    @Test
    fun `nested transactions join the outer one`() {
        val db = open()
        val bucket = db.bucket("b")
        val commitCount = commits(db)
        db.transaction {
            bucket.put("1", "a")
            db.transaction { bucket.put("2", "b") }
            bucket.put("3", "c")
        }
        assertEquals(1, commitCount())
        assertEquals(3, bucket.count())
    }

    @Test
    fun `a failure caught inside a transaction still dooms it`() {
        val db = open()
        val bucket = db.bucket("b")
        bucket.put("kept", "yes")

        assertFailsWith<DatabaseException> {
            db.transaction {
                bucket.put("lost", "no")
                try {
                    db.transaction { throw IllegalStateException("inner failure") }
                } catch (expected: IllegalStateException) {
                    // The caller swallows it, but the half-done inner work cannot be undone separately.
                }
                assertFailsWith<IllegalStateException> { bucket.put("later", "no") }
            }
        }

        assertEquals(listOf("kept"), bucket.scan { entries -> entries.map { it.key.decodeToString() }.toList() })
        db.verify()
    }

    @Test
    fun `an invalid key does not doom the transaction`() {
        val db = open()
        val bucket = db.bucket("b")
        db.transaction {
            bucket.put("ok", "1")
            assertFailsWith<IllegalArgumentException> { bucket.put(ByteArray(db.maxKeySize + 1), byteArrayOf(1)) }
            bucket.put("ok2", "2")
        }
        assertEquals(2, bucket.count())
    }

    @Test
    fun `writing while a scan is running is rejected but the transaction survives`() {
        val db = open()
        val bucket = db.bucket("b")
        repeat(10) { bucket.put(key(it), byteArrayOf(1)) }
        db.transaction {
            assertFailsWith<IllegalStateException> {
                bucket.scan { entries -> entries.forEach { bucket.delete(it.key) } }
            }
            bucket.put("after", "scan")
        }
        assertEquals(11, bucket.count())
    }

    @Test
    fun `a transaction larger than the cache commits and rolls back correctly`() {
        val db = open(DatabaseOptions(pageSize = 512, cachePages = 8, syncOnCommit = false))
        val bucket = db.bucket("b")
        db.transaction { repeat(4_000) { bucket.put(key(it), ByteArray(it % 200)) } }
        db.verify()
        val sizeAfterCommit = Files.size(file)

        assertFailsWith<IllegalStateException> {
            db.transaction {
                repeat(2_000) { bucket.delete(key(it)) }
                repeat(2_000) { bucket.put(key(10_000 + it), ByteArray(300)) }
                throw IllegalStateException("abort")
            }
        }
        assertEquals(4_000, bucket.count())
        assertEquals(sizeAfterCommit, Files.size(file))
        db.verify()
    }

    @Test
    fun `lifecycle misuse is detected instead of deadlocking`() {
        val db = open()
        val bucket = db.bucket("b")
        bucket.put("a", "1")
        assertFailsWith<IllegalStateException> { db.transaction { db.close() } }
        assertFailsWith<IllegalStateException> { bucket.scan { db.transaction { } } }
        db.close()
        assertFailsWith<IllegalStateException> { db.transaction { } }
    }

    @Test
    fun `concurrent transactions are serialized`() {
        val db = open()
        val counters = db.bucket("counters")
        counters.put("n", "0")
        val threads = 4
        val increments = 100
        val pool = Executors.newFixedThreadPool(threads)
        val failure = AtomicReference<Throwable>()
        repeat(threads) {
            pool.execute {
                try {
                    repeat(increments) {
                        db.transaction { counters.put("n", (counters.get("n")!!.toInt() + 1).toString()) }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals((threads * increments).toString(), counters.get("n"))
    }

    @Test
    fun `readers never observe a half applied transaction`() {
        val db = open()
        val bucket = db.bucket("b")
        bucket.put("a", "0")
        bucket.put("b", "0")
        val failure = AtomicReference<Throwable>()
        val writerDone = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        pool.execute {
            try {
                repeat(200) { i ->
                    db.transaction {
                        bucket.put("a", i.toString())
                        bucket.put("b", i.toString())
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                writerDone.countDown()
            }
        }
        repeat(2) {
            pool.execute {
                try {
                    while (writerDone.count > 0) {
                        val pair = bucket.scan { entries -> entries.map { it.value.decodeToString() }.toList() }
                        assertEquals(pair[0], pair[1])
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }
}
