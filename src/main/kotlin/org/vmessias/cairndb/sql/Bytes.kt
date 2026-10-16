package org.vmessias.cairndb.sql

import org.vmessias.cairndb.CorruptDatabaseException

/** Growable byte buffer with the primitives the SQL record formats are built from. */
internal class ByteSink(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(value: Int) {
        ensure(1)
        buf[size++] = value.toByte()
    }

    fun i64(value: Long) {
        ensure(8)
        for (shift in 56 downTo 0 step 8) buf[size++] = (value ushr shift).toByte()
    }

    /** Unsigned LEB128. */
    fun varUInt(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            u8(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        u8(v.toInt())
    }

    /** Zigzag-encoded signed LEB128, compact for small magnitudes of either sign. */
    fun varInt(value: Long) = varUInt((value shl 1) xor (value shr 63))

    fun bytes(source: ByteArray) {
        ensure(source.size)
        System.arraycopy(source, 0, buf, size, source.size)
        size += source.size
    }

    fun lengthPrefixed(source: ByteArray) {
        varUInt(source.size.toLong())
        bytes(source)
    }

    fun string(value: String) = lengthPrefixed(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Reader for the formats written by [ByteSink]; malformed input raises [CorruptDatabaseException]. */
internal class ByteSource(private val data: ByteArray) {
    private var pos = 0

    val remaining: Int get() = data.size - pos

    private fun malformed(): Nothing = throw CorruptDatabaseException("Malformed SQL record")

    fun u8(): Int {
        if (pos >= data.size) malformed()
        return data[pos++].toInt() and 0xFF
    }

    fun i64(): Long {
        if (remaining < 8) malformed()
        var value = 0L
        repeat(8) { value = (value shl 8) or (data[pos++].toLong() and 0xFF) }
        return value
    }

    fun varUInt(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) malformed()
        }
    }

    fun varInt(): Long {
        val v = varUInt()
        return (v ushr 1) xor -(v and 1)
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || count > remaining) malformed()
        return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun lengthPrefixed(): ByteArray {
        val length = varUInt()
        if (length < 0 || length > remaining) malformed()
        return bytes(length.toInt())
    }

    fun string(): String = lengthPrefixed().toString(Charsets.UTF_8)
}
