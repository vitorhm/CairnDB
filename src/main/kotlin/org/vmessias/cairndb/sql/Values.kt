package org.vmessias.cairndb.sql

import java.nio.ByteBuffer
import java.util.Arrays

/**
 * SQL values are `null`, [Long], [Double], [String] and [ByteArray]. Comparisons and logical
 * operators also produce [Boolean], which is stored and returned as the INTEGER 1 or 0.
 */
internal object Values {
    fun normalize(value: Any?): Any? = if (value is Boolean) (if (value) 1L else 0L) else value

    fun typeName(value: Any?): String = when (value) {
        null -> "NULL"
        is Long, is Boolean -> "INTEGER"
        is Double -> "REAL"
        is String -> "TEXT"
        is ByteArray -> "BLOB"
        else -> value::class.simpleName ?: "?"
    }

    /** Three-valued truth of a condition: null means unknown. */
    fun truth(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is Long -> value != 0L
        is Double -> value != 0.0
        else -> throw SqlException("A ${typeName(value)} value cannot be used as a condition")
    }

    /** Orders two non-null values; numbers compare across INTEGER and REAL, anything else needs equal types. */
    fun compare(a: Any, b: Any): Int {
        val x = normalize(a)
        val y = normalize(b)
        return when {
            x is Long && y is Long -> x.compareTo(y)
            x is Long && y is Double -> compareLongDouble(x, y)
            x is Double && y is Long -> -compareLongDouble(y, x)
            x is Double && y is Double -> if (x < y) -1 else if (x > y) 1 else 0
            x is String && y is String -> compareText(x, y)
            x is ByteArray && y is ByteArray -> Arrays.compareUnsigned(x, y)
            else -> throw SqlException("Cannot compare ${typeName(x)} with ${typeName(y)}")
        }
    }

    /** Ordering for ORDER BY: NULL sorts before everything else. */
    fun compareForOrder(a: Any?, b: Any?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        else -> compare(a, b)
    }

    /** Code point order, which is also the byte order of the UTF-8 encoding used for keys. */
    fun compareText(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return if (x < y) -1 else 1
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    private fun compareLongDouble(l: Long, d: Double): Int {
        if (d.isNaN()) return 0
        if (d >= 9.223372036854775807E18) return -1
        if (d < -9.223372036854775808E18) return 1
        val truncated = d.toLong()
        if (l != truncated) return l.compareTo(truncated)
        val fraction = d - truncated.toDouble()
        return if (fraction > 0) -1 else if (fraction < 0) 1 else 0
    }

    /** A value usable as a hash key (for GROUP BY and DISTINCT): arrays compare by content and equal numbers share a key. */
    fun hashable(value: Any?): Any? = when (val v = normalize(value)) {
        is ByteArray -> ByteBuffer.wrap(v)
        is Double -> if (v >= -9.223372036854775808E18 && v < 9.223372036854775807E18 && v == Math.floor(v)) v.toLong() else v
        else -> v
    }

    /** False when [text] holds an unpaired surrogate, which UTF-8 (the storage and key encoding) cannot represent. */
    fun isWellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return false
            } else {
                i++
            }
        }
        return true
    }

    /** Same type and same content; unlike [compare] it never throws and 1 is not 1.0. */
    fun identical(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        else -> a == b
    }

    /** SQL LIKE: `%` matches any run of characters, `_` exactly one; ASCII letters match case-insensitively. */
    fun like(text: String, pattern: String): Boolean {
        val t = text.codePoints().toArray()
        val p = pattern.codePoints().toArray()
        var ti = 0
        var pi = 0
        var starPattern = -1
        var starText = 0
        while (ti < t.size) {
            if (pi < p.size && p[pi] == '%'.code) {
                starPattern = pi++
                starText = ti
            } else if (pi < p.size && (p[pi] == '_'.code || foldAscii(p[pi]) == foldAscii(t[ti]))) {
                pi++
                ti++
            } else if (starPattern >= 0) {
                pi = starPattern + 1
                ti = ++starText
            } else {
                return false
            }
        }
        while (pi < p.size && p[pi] == '%'.code) pi++
        return pi == p.size
    }

    private fun foldAscii(codePoint: Int): Int = if (codePoint in 'A'.code..'Z'.code) codePoint + 32 else codePoint
}
