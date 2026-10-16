package org.vmessias.cairndb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketTest : DatabaseTestBase() {
    @Test
    fun `put get and overwrite`() {
        val bucket = open().bucket("users")
        assertNull(bucket.get("a"))
        bucket.put("a", "1")
        bucket.put("b", "2")
        bucket.put("a", "one")
        assertEquals("one", bucket.get("a"))
        assertEquals("2", bucket.get("b"))
        assertEquals(2, bucket.count())
    }

    @Test
    fun `delete reports whether the key existed`() {
        val bucket = open().bucket("users")
        bucket.put("a", "1")
        assertTrue(bucket.delete("a"))
        assertFalse(bucket.delete("a"))
        assertNull(bucket.get("a"))
        assertFalse(bucket.contains("a".toByteArray()))
    }

    @Test
    fun `empty key and empty value are allowed`() {
        val bucket = open().bucket("b")
        bucket.put(ByteArray(0), ByteArray(0))
        assertContentEquals(ByteArray(0), bucket.get(ByteArray(0)))
        assertEquals(1, bucket.count())
    }

    @Test
    fun `keys are ordered as unsigned bytes`() {
        val bucket = open().bucket("b")
        val keys = listOf(byteArrayOf(0x7F), byteArrayOf(0x80.toByte()), byteArrayOf(0xFF.toByte()), byteArrayOf(0), byteArrayOf(0, 0))
        keys.shuffled(Random(1)).forEach { bucket.put(it, byteArrayOf(1)) }
        val scanned = scanAll(bucket).map { it.key.toList() }
        assertEquals(keys.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) }.map { it.toList() }, scanned)
    }

    @Test
    fun `scan honours inclusive lower and exclusive upper bounds`() {
        val bucket = open().bucket("b")
        repeat(500) { bucket.put(key(it), key(it)) }
        val range = scanAll(bucket, key(100), key(200))
        assertEquals((100 until 200).map { key(it).toList() }, range.map { it.key.toList() })
        assertEquals(500, scanAll(bucket).size)
        assertEquals(400, scanAll(bucket, from = key(100)).size)
        assertEquals(100, scanAll(bucket, to = key(100)).size)
        assertEquals(emptyList(), scanAll(bucket, key(9999)))
        assertEquals(emptyList(), scanAll(bucket, key(50), key(50)))
    }

    @Test
    fun `scan from a missing key starts at the next one`() {
        val bucket = open().bucket("b")
        listOf(10, 20, 30).forEach { bucket.put(key(it), byteArrayOf(0)) }
        assertEquals(listOf(key(20), key(30)).map { it.toList() }, scanAll(bucket, key(15)).map { it.key.toList() })
    }

    @Test
    fun `large values spill to overflow pages and round trip`() {
        val db = open()
        val bucket = db.bucket("blobs")
        val sizes = listOf(0, 1, 100, 400, 509, 510, 5_000, 100_000)
        val values = sizes.associateWith { Random(it).nextBytes(it) }
        values.forEach { (size, value) -> bucket.put(key(size), value) }
        values.forEach { (size, value) -> assertContentEquals(value, bucket.get(key(size)), "size $size") }
        db.verify()
        values.keys.forEach { assertTrue(bucket.delete(key(it))) }
        db.verify()
        assertEquals(0, bucket.count())
    }

    @Test
    fun `replacing a large value with a small one releases its overflow pages`() {
        val db = open()
        val bucket = db.bucket("b")
        bucket.put(key(1).copyOf(), ByteArray(50_000))
        bucket.put(key(1), "small".toByteArray())
        db.verify()
        assertEquals("small", bucket.get("key-00000001"))
    }

    @Test
    fun `keys longer than the maximum are rejected`() {
        val db = open()
        val bucket = db.bucket("b")
        assertEquals(64, db.maxKeySize)
        bucket.put(ByteArray(64) { 1 }, byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { bucket.put(ByteArray(65) { 1 }, byteArrayOf(1)) }
        assertNull(bucket.get(ByteArray(65) { 1 }))
        assertEquals(1, bucket.count())
    }

    @Test
    fun `writing inside a scan fails instead of deadlocking`() {
        val bucket = open().bucket("b")
        bucket.put("a", "1")
        assertFailsWith<IllegalStateException> {
            bucket.scan { entries -> entries.forEach { bucket.put("c", "3") } }
        }
        assertEquals(1, bucket.count())
    }

    @Test
    fun `data survives reopening the file`() {
        open().use { db ->
            val bucket = db.bucket("b")
            repeat(2_000) { bucket.put(key(it), ByteArray(it % 300) { b -> b.toByte() }) }
        }
        open().use { db ->
            val bucket = db.bucket("b")
            assertEquals(2_000, bucket.count())
            repeat(2_000) { assertContentEquals(ByteArray(it % 300) { b -> b.toByte() }, bucket.get(key(it))) }
            db.verify()
        }
    }
}
