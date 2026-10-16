package org.vmessias.cairndb

import java.nio.file.Files
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compares the B+Tree with a [TreeMap] under long random sequences of operations, on tiny pages. */
class RandomizedModelTest : DatabaseTestBase() {
    private class ByteKey(val bytes: ByteArray) : Comparable<ByteKey> {
        override fun compareTo(other: ByteKey) = java.util.Arrays.compareUnsigned(bytes, other.bytes)
    }

    private fun runModel(seed: Long, keySpace: Int, operations: Int, maxValue: Int, variableKeys: Boolean) {
        Files.deleteIfExists(file)
        val random = Random(seed)
        var db = open()
        var bucket = db.bucket("model")
        val model = TreeMap<ByteKey, ByteArray>()

        fun randomKey(): ByteArray {
            val n = random.nextInt(keySpace)
            return if (variableKeys) Random(n.toLong()).nextBytes(1 + n % 60) else key(n)
        }

        repeat(operations) { step ->
            when (random.nextInt(10)) {
                in 0..4 -> {
                    val k = randomKey()
                    val v = random.nextBytes(if (random.nextInt(50) == 0) random.nextInt(maxValue * 20) else random.nextInt(maxValue))
                    bucket.put(k, v)
                    model[ByteKey(k)] = v
                }
                in 5..7 -> {
                    val k = randomKey()
                    assertEquals(model.remove(ByteKey(k)) != null, bucket.delete(k))
                }
                else -> {
                    val k = randomKey()
                    val expected = model[ByteKey(k)]
                    val actual = bucket.get(k)
                    if (expected == null) assertEquals(null, actual) else assertContentEquals(expected, actual)
                }
            }
            if (step % 1_000 == 999) {
                db.verify()
                assertEquals(model.size.toLong(), bucket.count())
            }
            if (step % 4_000 == 3_999) {
                db.close()
                db = open()
                bucket = db.bucket("model")
            }
        }

        db.verify()
        val scanned = scanAll(bucket)
        assertEquals(model.size, scanned.size)
        scanned.zip(model.entries).forEach { (entry, expected) ->
            assertContentEquals(expected.key.bytes, entry.key)
            assertContentEquals(expected.value, entry.value)
        }
        val from = model.keys.elementAtOrNull(model.size / 3)
        val to = model.keys.elementAtOrNull(model.size * 2 / 3)
        if (from != null && to != null) {
            val range = scanAll(bucket, from.bytes, to.bytes)
            assertEquals(model.subMap(from, to).size, range.size)
        }
    }

    @Test
    fun `fixed keys with mostly small values`() = runModel(seed = 1, keySpace = 600, operations = 20_000, maxValue = 40, variableKeys = false)

    @Test
    fun `fixed keys with large values`() = runModel(seed = 2, keySpace = 150, operations = 6_000, maxValue = 200, variableKeys = false)

    @Test
    fun `variable length keys`() = runModel(seed = 3, keySpace = 2_000, operations = 20_000, maxValue = 60, variableKeys = true)

    @Test
    fun `heavy churn on a small key space keeps free list consistent`() =
        runModel(seed = 4, keySpace = 40, operations = 15_000, maxValue = 100, variableKeys = false)

    @Test
    fun `ascending then descending bulk delete empties the tree`() {
        val db = open()
        val bucket = db.bucket("b")
        val n = 5_000
        repeat(n) { bucket.put(key(it), ByteArray(20)) }
        db.verify()
        for (i in n - 1 downTo 0) {
            assertTrue(bucket.delete(key(i)))
            if (i % 500 == 0) db.verify()
        }
        assertEquals(0, bucket.count())
        db.verify()
        val sizeAfterEmptying = Files.size(file)
        repeat(n) { bucket.put(key(it), ByteArray(20)) }
        assertEquals(sizeAfterEmptying, Files.size(file), "freed pages must be reused")
    }
}
