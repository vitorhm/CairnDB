package org.vmessias.cairndb.sql

import org.vmessias.cairndb.CorruptDatabaseException

/**
 * Order-preserving key encoding: comparing two encoded keys as unsigned bytes gives the same result as
 * comparing the values column by column. Every column encoding is prefix-free, so concatenated
 * columns keep that property and all keys sharing leading column values form one contiguous range.
 *
 * ```
 * INTEGER  8 bytes big-endian with the sign bit flipped
 * REAL     8 bytes big-endian, sign bit flipped (or all bits when negative); -0.0 is stored as 0.0
 * TEXT     UTF-8 with 00 escaped as 00 FF, then the terminator 00 01
 * BLOB     like TEXT, over the raw bytes
 * ```
 */
internal object KeyCodec {
    fun append(out: ByteSink, value: Any, type: ColumnType) {
        when (type) {
            ColumnType.INTEGER -> out.i64((value as Long) xor Long.MIN_VALUE)
            ColumnType.REAL -> {
                val bits = java.lang.Double.doubleToLongBits(if (value as Double == 0.0) 0.0 else value)
                out.i64(if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE)
            }
            ColumnType.TEXT -> escaped(out, (value as String).toByteArray(Charsets.UTF_8))
            ColumnType.BLOB -> escaped(out, value as ByteArray)
        }
    }

    fun encode(value: Any, type: ColumnType): ByteArray = ByteSink().also { append(it, value, type) }.toByteArray()

    /** Key of a row of a table that has no primary key. */
    fun rowId(id: Long): ByteArray = encode(id, ColumnType.INTEGER)

    /** Key of [row] for a table with the primary key of [schema]; the key columns must be non-null. */
    fun primaryKey(schema: TableSchema, row: Array<Any?>): ByteArray {
        val out = ByteSink()
        for (index in schema.primaryKey) append(out, row[index]!!, schema.columns[index].type)
        return out.toByteArray()
    }

    /**
     * Smallest key greater than every key that starts with [prefix], or null if there is none (the
     * prefix is all 0xFF bytes). Together with the prefix itself it bounds the range of its extensions.
     */
    fun prefixEnd(prefix: ByteArray): ByteArray? {
        var end = prefix.size
        while (end > 0 && prefix[end - 1] == 0xFF.toByte()) end--
        if (end == 0) return null
        return prefix.copyOf(end).also { it[end - 1]++ }
    }

    private fun escaped(out: ByteSink, bytes: ByteArray) {
        for (b in bytes) {
            out.u8(b.toInt())
            if (b.toInt() == 0) out.u8(0xFF)
        }
        out.u8(0)
        out.u8(1)
    }
}

/**
 * Row record: the number of columns, then for each one a type tag followed by its payload. Rows
 * written before a column was added simply have fewer entries and read back as NULL there.
 *
 * ```
 * 0 NULL | 1 INTEGER zigzag varint | 2 REAL 8 bytes | 3 TEXT length + UTF-8 | 4 BLOB length + bytes
 * ```
 */
internal object RowCodec {
    fun encode(values: Array<Any?>): ByteArray {
        val out = ByteSink(16 + 8 * values.size)
        out.varUInt(values.size.toLong())
        for (value in values) {
            when (value) {
                null -> out.u8(0)
                is Long -> {
                    out.u8(ColumnType.INTEGER.tag)
                    out.varInt(value)
                }
                is Double -> {
                    out.u8(ColumnType.REAL.tag)
                    out.i64(java.lang.Double.doubleToLongBits(value))
                }
                is String -> {
                    out.u8(ColumnType.TEXT.tag)
                    out.string(value)
                }
                is ByteArray -> {
                    out.u8(ColumnType.BLOB.tag)
                    out.lengthPrefixed(value)
                }
                else -> throw IllegalArgumentException("Cannot store ${value::class}")
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, columnCount: Int): Array<Any?> {
        val input = ByteSource(bytes)
        val stored = input.varUInt()
        if (stored < 0 || stored > columnCount || stored > input.remaining) {
            throw CorruptDatabaseException("Row has $stored values but the table has $columnCount columns")
        }
        val row = arrayOfNulls<Any?>(columnCount)
        for (i in 0 until stored.toInt()) {
            row[i] = when (val tag = input.u8()) {
                0 -> null
                ColumnType.INTEGER.tag -> input.varInt()
                ColumnType.REAL.tag -> java.lang.Double.longBitsToDouble(input.i64())
                ColumnType.TEXT.tag -> input.string()
                ColumnType.BLOB.tag -> input.lengthPrefixed()
                else -> throw CorruptDatabaseException("Unknown value tag $tag in a row")
            }
        }
        if (input.remaining != 0) throw CorruptDatabaseException("Trailing bytes after a row")
        return row
    }
}
