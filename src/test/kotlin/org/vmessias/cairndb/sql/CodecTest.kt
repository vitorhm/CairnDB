package org.vmessias.cairndb.sql

import org.vmessias.cairndb.CorruptDatabaseException
import java.util.Arrays
import kotlin.math.sign
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {
    private val random = Random(20240607)

    private fun randomText(): String {
        val alphabet = listOf("a", "b", "z", "\u0000", "\u0001", "\u00e9", "\u20ac", "\uFFFF", "\uD83D\uDE00", "\uD800\uDC00", " ")
        return buildString { repeat(random.nextInt(0, 6)) { append(alphabet.random(random)) } }
    }

    private fun randomBlob(): ByteArray {
        val choices = intArrayOf(0, 1, 2, 127, 128, 254, 255)
        return ByteArray(random.nextInt(0, 6)) { choices.random(random).toByte() }
    }

    private fun randomLong(): Long = when (random.nextInt(4)) {
        0 -> random.nextLong()
        1 -> random.nextLong(-5, 6)
        2 -> listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).random(random)
        else -> random.nextLong(-1_000_000, 1_000_000)
    }

    private fun randomDouble(): Double = when (random.nextInt(4)) {
        0 -> random.nextDouble(-1e6, 1e6)
        1 -> listOf(0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).random(random)
        2 -> java.lang.Double.longBitsToDouble(random.nextLong()).let { if (it.isNaN()) 0.5 else it }
        else -> random.nextInt(-5, 6) / 4.0
    }

    private fun compareKeys(a: ByteArray, b: ByteArray): Int = Arrays.compareUnsigned(a, b).sign

    @Test
    fun `integer keys keep numeric order`() {
        repeat(5000) {
            val a = randomLong()
            val b = randomLong()
            assertEquals(a.compareTo(b).sign, compareKeys(KeyCodec.encode(a, ColumnType.INTEGER), KeyCodec.encode(b, ColumnType.INTEGER)), "$a vs $b")
        }
    }

    @Test
    fun `real keys keep numeric order and merge the two zeros`() {
        repeat(5000) {
            val a = randomDouble()
            val b = randomDouble()
            val expected = if (a == b) 0 else a.compareTo(b).sign
            assertEquals(expected, compareKeys(KeyCodec.encode(a, ColumnType.REAL), KeyCodec.encode(b, ColumnType.REAL)), "$a vs $b")
            assertEquals(expected, Values.compare(a, b).sign, "Values.compare $a vs $b")
        }
        assertContentEquals(KeyCodec.encode(0.0, ColumnType.REAL), KeyCodec.encode(-0.0, ColumnType.REAL))
    }

    @Test
    fun `text keys keep code point order`() {
        repeat(5000) {
            val a = randomText()
            val b = randomText()
            val expected = Values.compare(a, b).sign
            assertEquals(expected, compareKeys(KeyCodec.encode(a, ColumnType.TEXT), KeyCodec.encode(b, ColumnType.TEXT)), "'$a' vs '$b'")
            val byCodePoints = a.codePoints().toArray().toList().zip(b.codePoints().toArray().toList()).firstOrNull { it.first != it.second }
                ?.let { it.first.compareTo(it.second).sign } ?: a.codePointCount(0, a.length).compareTo(b.codePointCount(0, b.length)).sign
            assertEquals(byCodePoints, expected, "'$a' vs '$b' by code point")
        }
    }

    @Test
    fun `blob keys keep unsigned byte order`() {
        repeat(5000) {
            val a = randomBlob()
            val b = randomBlob()
            assertEquals(Arrays.compareUnsigned(a, b).sign, compareKeys(KeyCodec.encode(a, ColumnType.BLOB), KeyCodec.encode(b, ColumnType.BLOB)))
            assertEquals(Arrays.compareUnsigned(a, b).sign, Values.compare(a, b).sign)
        }
    }

    @Test
    fun `encodings are prefix free`() {
        val texts = List(300) { randomText() }.distinct()
        for (a in texts) for (b in texts) {
            if (a == b) continue
            val x = KeyCodec.encode(a, ColumnType.TEXT)
            val y = KeyCodec.encode(b, ColumnType.TEXT)
            assertTrue(x.size > y.size || !y.copyOf(x.size).contentEquals(x), "'$a' is a prefix of '$b'")
        }
    }

    @Test
    fun `composite keys sort like tuples`() {
        repeat(5000) {
            val a = randomText() to randomLong()
            val b = randomText() to randomLong()
            val keyA = KeyCodec.encode(a.first, ColumnType.TEXT) + KeyCodec.encode(a.second, ColumnType.INTEGER)
            val keyB = KeyCodec.encode(b.first, ColumnType.TEXT) + KeyCodec.encode(b.second, ColumnType.INTEGER)
            val expected = Values.compare(a.first, b.first).sign.takeIf { it != 0 } ?: a.second.compareTo(b.second).sign
            assertEquals(expected, compareKeys(keyA, keyB), "$a vs $b")
        }
    }

    @Test
    fun `prefix end bounds exactly the extensions of a prefix`() {
        assertNull(KeyCodec.prefixEnd(byteArrayOf()))
        assertNull(KeyCodec.prefixEnd(byteArrayOf(-1, -1)))
        assertContentEquals(byteArrayOf(2), KeyCodec.prefixEnd(byteArrayOf(1, -1, -1)))
        assertContentEquals(byteArrayOf(1, 1), KeyCodec.prefixEnd(byteArrayOf(1, 0)))

        repeat(5000) {
            val prefix = randomBlob()
            val other = randomBlob() + randomBlob().take(random.nextInt(0, 2))
            val extension = prefix + randomBlob()
            val end = KeyCodec.prefixEnd(prefix)
            if (end != null) assertTrue(Arrays.compareUnsigned(extension, end) < 0, "extension must stay below the end")
            assertTrue(Arrays.compareUnsigned(extension, prefix) >= 0)
            val extends = other.size >= prefix.size && other.copyOf(prefix.size).contentEquals(prefix)
            if (!extends && Arrays.compareUnsigned(other, prefix) > 0) {
                assertNotNull(end, "a key above the prefix that is no extension implies an end exists")
                assertTrue(Arrays.compareUnsigned(other, end) >= 0, "keys after the range must not be below the end")
            }
        }
    }

    @Test
    fun `rows round trip`() {
        repeat(2000) {
            val row = Array<Any?>(random.nextInt(0, 7)) {
                when (random.nextInt(5)) {
                    0 -> null
                    1 -> randomLong()
                    2 -> randomDouble()
                    3 -> randomText()
                    else -> randomBlob()
                }
            }
            val decoded = RowCodec.decode(RowCodec.encode(row), row.size)
            assertEquals(row.map { if (it is ByteArray) it.toList() else it }, decoded.map { if (it is ByteArray) it.toList() else it })
        }
    }

    @Test
    fun `negative zero survives in rows`() {
        val decoded = RowCodec.decode(RowCodec.encode(arrayOf(-0.0)), 1)
        assertEquals(1.0 / -0.0, 1.0 / (decoded[0] as Double))
    }

    @Test
    fun `rows written before a column was added are padded with null`() {
        val decoded = RowCodec.decode(RowCodec.encode(arrayOf(1L, "a")), 4)
        assertEquals(listOf<Any?>(1L, "a", null, null), decoded.toList())
    }

    @Test
    fun `malformed rows are reported as corruption and nothing else`() {
        val valid = RowCodec.encode(arrayOf(1L, "hello", byteArrayOf(1, 2, 3), 2.5, null))
        assertFailsWith<CorruptDatabaseException> { RowCodec.decode(valid, 3) }
        assertFailsWith<CorruptDatabaseException> { RowCodec.decode(valid + byteArrayOf(0), 5) }
        assertFailsWith<CorruptDatabaseException> { RowCodec.decode(byteArrayOf(), 5) }
        assertFailsWith<CorruptDatabaseException> { RowCodec.decode(byteArrayOf(1, 9), 1) }
        repeat(20_000) {
            val bytes = if (random.nextBoolean()) {
                random.nextBytes(random.nextInt(0, 24))
            } else {
                valid.copyOf(random.nextInt(0, valid.size + 1)).also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt().toByte() }
            }
            try {
                RowCodec.decode(bytes, 5)
            } catch (_: CorruptDatabaseException) {
                // the only acceptable failure
            }
        }
    }

    @Test
    fun `schemas round trip`() {
        val schema = TableSchema(
            "Orders",
            listOf(Column("Id", ColumnType.INTEGER, true), Column("note", ColumnType.TEXT, false), Column("blob", ColumnType.BLOB, false), Column("r", ColumnType.REAL, true)),
            listOf(2, 0),
            nextRowId = -42,
        )
        val decoded = SchemaCodec.decode(SchemaCodec.encode(schema))
        assertEquals("Orders", decoded.name)
        assertEquals(schema.columns.map { Triple(it.name, it.type, it.notNull) }, decoded.columns.map { Triple(it.name, it.type, it.notNull) })
        assertEquals(listOf(2, 0), decoded.primaryKey)
        assertEquals(-42L, decoded.nextRowId)
        assertEquals(2, decoded.indexOf("BLOB"))
    }

    @Test
    fun `malformed schemas are reported as corruption and nothing else`() {
        val valid = SchemaCodec.encode(TableSchema("t", listOf(Column("a", ColumnType.INTEGER, true), Column("b", ColumnType.TEXT, false)), listOf(0)))
        repeat(20_000) {
            val bytes = valid.copyOf(random.nextInt(0, valid.size + 1)).also { if (it.isNotEmpty() && random.nextBoolean()) it[random.nextInt(it.size)] = random.nextInt().toByte() }
            try {
                SchemaCodec.decode(bytes)
            } catch (_: CorruptDatabaseException) {
                // the only acceptable failure
            }
        }
    }
}
