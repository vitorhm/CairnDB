package org.vmessias.cairndb

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest : DatabaseTestBase() {
    @Test
    fun `new file has the documented header`() {
        open().close()
        val bytes = Files.readAllBytes(file)
        assertEquals(512L * 2, bytes.size.toLong())
        assertEquals("CairnDB", String(bytes, 0, 7))
        assertEquals(2, java.nio.ByteBuffer.wrap(bytes).getInt(20), "format version")
    }

    @Test
    fun `buckets are created on demand listed and dropped`() {
        val db = open()
        assertEquals(emptyList(), db.bucketNames())
        db.bucket("b").put("k", "v")
        db.bucket("a").put("k", "v")
        assertEquals(listOf("a", "b"), db.bucketNames())
        assertTrue(db.dropBucket("a"))
        assertFalse(db.dropBucket("a"))
        assertEquals(listOf("b"), db.bucketNames())
        db.verify()
    }

    @Test
    fun `dropped bucket handle fails and a recreated bucket starts empty`() {
        val db = open()
        val old = db.bucket("t")
        repeat(1_000) { old.put(key(it), ByteArray(100)) }
        db.dropBucket("t")
        assertFailsWith<DatabaseException> { old.get("x") }
        db.verify()
        val fresh = db.bucket("t")
        assertEquals(0, fresh.count())
        assertNull(fresh.get(key(1)))
    }

    @Test
    fun `dropping a bucket returns all its pages to the free list`() {
        val db = open()
        val bucket = db.bucket("t")
        repeat(2_000) { bucket.put(key(it), ByteArray(300)) }
        val full = Files.size(file)
        db.dropBucket("t")
        val again = db.bucket("t")
        repeat(2_000) { again.put(key(it), ByteArray(300)) }
        assertEquals(full, Files.size(file))
        db.verify()
    }

    @Test
    fun `buckets are isolated and persisted`() {
        open().use { db ->
            db.bucket("a").put("k", "from a")
            db.bucket("b").put("k", "from b")
        }
        open().use { db ->
            assertEquals("from a", db.bucket("a").get("k"))
            assertEquals("from b", db.bucket("b").get("k"))
        }
    }

    @Test
    fun `a second open of the same file is refused`() {
        open()
        assertFailsWith<DatabaseLockedException> { Database.open(file) }
    }

    @Test
    fun `lock is released on close`() {
        open().close()
        open()
    }

    @Test
    fun `operations after close fail`() {
        val db = open()
        val bucket = db.bucket("b")
        db.close()
        assertFailsWith<IllegalStateException> { bucket.get("k") }
        db.close()
    }

    @Test
    fun `invalid options are rejected`() {
        assertFailsWith<IllegalArgumentException> { DatabaseOptions(pageSize = 1000) }
        assertFailsWith<IllegalArgumentException> { DatabaseOptions(pageSize = 256) }
        assertFailsWith<IllegalArgumentException> { DatabaseOptions(pageSize = 65536) }
        assertFailsWith<IllegalArgumentException> { DatabaseOptions(cachePages = 1) }
    }

    @Test
    fun `existing file keeps its original page size`() {
        open(DatabaseOptions(pageSize = 1024, syncOnCommit = false)).use { it.bucket("b").put("k", "v") }
        open(DatabaseOptions(pageSize = 4096, syncOnCommit = false)).use {
            assertEquals(128, it.maxKeySize)
            assertEquals("v", it.bucket("b").get("k"))
        }
    }

    @Test
    fun `files that are not databases are rejected`() {
        Files.write(file, ByteArray(2000) { 7 })
        assertFailsWith<CorruptDatabaseException> { Database.open(file) }
        Files.write(file, ByteArray(10))
        assertFailsWith<CorruptDatabaseException> { Database.open(file) }
    }

    @Test
    fun `header corruption and truncation are detected`() {
        open().use { db -> repeat(500) { db.bucket("b").put(key(it), ByteArray(50)) } }
        val original = Files.readAllBytes(file)

        val flipped = original.copyOf().also { it[24] = (it[24] + 1).toByte() }
        Files.write(file, flipped)
        assertFailsWith<CorruptDatabaseException> { Database.open(file) }

        Files.write(file, original.copyOf(original.size - 512))
        assertFailsWith<CorruptDatabaseException> { Database.open(file) }

        Files.write(file, original)
        open().use { it.verify() }
    }

    @Test
    fun `verify detects a damaged tree page`() {
        open().use { db -> repeat(500) { db.bucket("b").put(key(it), ByteArray(50)) } }
        java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(ByteArray(512) { 0x55 }), 512L * 2)
        }
        open().use { db -> assertFailsWith<CorruptDatabaseException> { db.verify() } }
    }

    @Test
    fun `readers run concurrently with a writer`() {
        val db = open(DatabaseOptions(pageSize = 512, cachePages = 16, syncOnCommit = false))
        val bucket = db.bucket("b")
        repeat(300) { bucket.put(key(it), key(it)) }

        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)
        repeat(4) { reader ->
            pool.execute {
                try {
                    start.await()
                    repeat(400) { i ->
                        val n = (i * 7 + reader) % 300
                        assertContentEquals(key(n), bucket.get(key(n)))
                        if (i % 50 == 0) assertTrue(bucket.count() >= 300)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        pool.execute {
            try {
                start.await()
                repeat(300) { bucket.put(key(1000 + it), key(1000 + it)) }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(600, bucket.count())
        db.verify()
    }

    @Test
    fun `small cache still gives correct results`() {
        val db = open(DatabaseOptions(pageSize = 512, cachePages = 8, syncOnCommit = false))
        val bucket = db.bucket("b")
        repeat(3_000) { bucket.put(key(it), ByteArray(it % 700)) }
        repeat(3_000) { assertEquals(it % 700, bucket.get(key(it))!!.size) }
        assertEquals(3_000, bucket.count())
        db.verify()
    }

    @Test
    fun `sync on commit mode works`() {
        open(DatabaseOptions(pageSize = 4096, syncOnCommit = true)).use { db ->
            val bucket = db.bucket("b")
            repeat(50) { bucket.put(key(it), ByteArray(10)) }
            assertEquals(50, bucket.count())
        }
    }
}
