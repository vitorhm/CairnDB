package org.vmessias.cairndb.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionTest : SqlTestBase() {
    private val db by lazy { open() }

    private fun scalar(expression: String, vararg params: Any?): Any? = db.scalar("SELECT $expression", *params)

    @Test
    fun `integer arithmetic`() {
        assertEquals(7L, scalar("1 + 2 * 3"))
        assertEquals(9L, scalar("(1 + 2) * 3"))
        assertEquals(3L, scalar("10 / 3"))
        assertEquals(-3L, scalar("-10 / 3"))
        assertEquals(1L, scalar("10 % 3"))
        assertEquals(-1L, scalar("-10 % 3"))
        assertEquals(9L, scalar("7 - -2"))
        assertEquals(-6L, scalar("2 * -3"))
        assertEquals(Long.MIN_VALUE, scalar("-9223372036854775808"))
        assertEquals(5L, scalar("+5"))
    }

    @Test
    fun `real arithmetic promotes integers`() {
        assertEquals(2.5, scalar("10 / 4.0"))
        assertEquals(3.5, scalar("1 + 2.5"))
        assertEquals(1000.0, scalar("1e3"))
        assertEquals(0.5, scalar(".5"))
        assertEquals(-2.5, scalar("-2.5"))
    }

    @Test
    fun `arithmetic errors are reported instead of wrapping around`() {
        db.fails("SELECT 9223372036854775807 + 1")
        db.fails("SELECT -9223372036854775808 - 1")
        db.fails("SELECT 9223372036854775807 * 2")
        db.fails("SELECT -(-9223372036854775808)")
        db.fails("SELECT -9223372036854775808 / -1")
        db.fails("SELECT 1 / 0")
        db.fails("SELECT 1 % 0")
        db.fails("SELECT 1.0 / 0")
        db.fails("SELECT 'a' + 1")
        db.fails("SELECT x'00' * 2")
    }

    @Test
    fun `comparisons across numeric types are exact`() {
        assertEquals(1L, scalar("1 = 1.0"))
        assertEquals(1L, scalar("2 > 1.5"))
        assertEquals(1L, scalar("9007199254740993 > 9007199254740992.0"))
        assertEquals(0L, scalar("9007199254740993 = 9007199254740992.0"))
        assertEquals(1L, scalar("'a' < 'b'"))
        assertEquals(1L, scalar("'' < 'a'"))
        assertEquals(1L, scalar("x'01' < x'FF'"))
        assertEquals(1L, scalar("1 <> 2"))
        assertEquals(1L, scalar("1 != 2"))
        assertEquals(1L, scalar("2 == 2"))
        assertEquals(1L, scalar("1 <= 1"))
        assertEquals(0L, scalar("1 >= 2"))
    }

    @Test
    fun `text compares by code point not by UTF-16 unit`() {
        assertEquals(1L, scalar("? < ?", "\uFFFF", "\uD83D\uDE00"))
        assertEquals(1L, scalar("? > ?", "\uD83D\uDE00", "\uFFFF"))
    }

    @Test
    fun `comparing incompatible types is an error`() {
        db.fails("SELECT 'a' = 1")
        db.fails("SELECT x'00' < 'a'")
        db.fails("SELECT 1 < x'00'")
    }

    @Test
    fun `null propagates through comparisons and arithmetic`() {
        assertNull(scalar("NULL = NULL"))
        assertNull(scalar("1 < NULL"))
        assertNull(scalar("NULL + 1"))
        assertNull(scalar("NULL || 'a'"))
        assertNull(scalar("-NULL"))
        assertEquals(1L, scalar("NULL IS NULL"))
        assertEquals(0L, scalar("1 IS NULL"))
        assertEquals(0L, scalar("NULL IS NOT NULL"))
        assertEquals(1L, scalar("1 IS NOT NULL"))
    }

    @Test
    fun `three valued logic`() {
        assertEquals(0L, scalar("NULL AND 0"))
        assertEquals(0L, scalar("0 AND NULL"))
        assertNull(scalar("NULL AND 1"))
        assertEquals(1L, scalar("NULL OR 1"))
        assertEquals(1L, scalar("1 OR NULL"))
        assertNull(scalar("NULL OR 0"))
        assertNull(scalar("NOT NULL"))
        assertEquals(0L, scalar("NOT 1"))
        assertEquals(1L, scalar("NOT 0"))
        assertEquals(1L, scalar("TRUE AND NOT FALSE"))
    }

    @Test
    fun `logic short circuits only when the result is decided`() {
        assertEquals(0L, scalar("0 AND 1 / 0 = 1"))
        assertEquals(1L, scalar("1 OR 1 / 0 = 1"))
        db.fails("SELECT 1 AND 1 / 0 = 1")
    }

    @Test
    fun `like is case insensitive for ASCII and supports wildcards`() {
        assertEquals(1L, scalar("'Hello' LIKE 'h%'"))
        assertEquals(1L, scalar("'Hello' LIKE 'h_llo'"))
        assertEquals(1L, scalar("'Hello' LIKE '%LL%'"))
        assertEquals(1L, scalar("'Hello' LIKE '%'"))
        assertEquals(1L, scalar("'' LIKE '%'"))
        assertEquals(1L, scalar("'a%b' LIKE 'a%b'"))
        assertEquals(0L, scalar("'abc' LIKE 'a_'"))
        assertEquals(0L, scalar("'' LIKE '_'"))
        assertEquals(1L, scalar("'Hello' NOT LIKE 'x%'"))
        assertEquals(1L, scalar("'ab' LIKE '%a%b%'"))
        assertEquals(0L, scalar("'ba' LIKE '%a%b%'"))
        assertNull(scalar("NULL LIKE 'a'"))
        assertNull(scalar("'a' LIKE NULL"))
        db.fails("SELECT 1 LIKE 'a'")
    }

    @Test
    fun `like with many wildcards stays fast`() {
        val subject = "a".repeat(5000)
        val pattern = "%a".repeat(60) + "b"
        val started = System.nanoTime()
        assertEquals(0L, scalar("? LIKE ?", subject, pattern))
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `in list`() {
        assertEquals(1L, scalar("2 IN (1, 2, 3)"))
        assertEquals(0L, scalar("5 IN (1, 2)"))
        assertNull(scalar("5 IN (1, NULL)"))
        assertEquals(1L, scalar("2 IN (NULL, 2)"))
        assertEquals(1L, scalar("5 NOT IN (1, 2)"))
        assertNull(scalar("5 NOT IN (1, NULL)"))
        assertNull(scalar("NULL IN (1)"))
        assertEquals(1L, scalar("'b' IN ('a', 'b')"))
        assertEquals(1L, scalar("2 IN (1.0, 2.0)"))
    }

    @Test
    fun `between`() {
        assertEquals(1L, scalar("5 BETWEEN 1 AND 10"))
        assertEquals(1L, scalar("1 BETWEEN 1 AND 10"))
        assertEquals(1L, scalar("10 BETWEEN 1 AND 10"))
        assertEquals(0L, scalar("11 BETWEEN 1 AND 10"))
        assertEquals(0L, scalar("5 BETWEEN 10 AND 1"))
        assertEquals(1L, scalar("5 NOT BETWEEN 1 AND 3"))
        assertNull(scalar("NULL BETWEEN 1 AND 2"))
        assertEquals(0L, scalar("5 BETWEEN NULL AND 3"))
        assertNull(scalar("2 BETWEEN NULL AND 3"))
    }

    @Test
    fun `concatenation converts numbers to text`() {
        assertEquals("ab1", scalar("'a' || 'b' || 1"))
        assertEquals("x2.5", scalar("'x' || 2.5"))
        assertEquals("ab", scalar("'a' || 'b'"))
        db.fails("SELECT 'a' || x'00'")
    }

    @Test
    fun `scalar functions`() {
        assertEquals("abc", scalar("LOWER('AbC')"))
        assertEquals("ABC", scalar("upper('aBc')"))
        assertEquals(5L, scalar("LENGTH('héllo')"))
        assertEquals(2L, scalar("LENGTH('x' || 'y')"))
        assertEquals(2L, scalar("LENGTH(x'0102')"))
        assertEquals(2L, scalar("LENGTH(?)", "\uD83D\uDE00\uD83D\uDE00"))
        assertEquals(3L, scalar("ABS(-3)"))
        assertEquals(2.5, scalar("ABS(-2.5)"))
        assertEquals(3L, scalar("COALESCE(NULL, NULL, 3, 4)"))
        assertNull(scalar("COALESCE(NULL, NULL)"))
        assertEquals("x", scalar("IFNULL(NULL, 'x')"))
        assertEquals(1L, scalar("IFNULL(1, 'x')"))
        assertNull(scalar("LOWER(NULL)"))
        assertNull(scalar("LENGTH(NULL)"))
        db.fails("SELECT ABS(-9223372036854775808)")
        db.fails("SELECT ABS('a')")
        db.fails("SELECT LOWER(1)")
    }

    @Test
    fun `typeof reports the storage class`() {
        assertEquals("integer", scalar("TYPEOF(1)"))
        assertEquals("real", scalar("TYPEOF(1.5)"))
        assertEquals("text", scalar("TYPEOF('a')"))
        assertEquals("blob", scalar("TYPEOF(x'00')"))
        assertEquals("null", scalar("TYPEOF(NULL)"))
        assertEquals("integer", scalar("TYPEOF(1 = 1)"))
    }

    @Test
    fun `function errors`() {
        db.fails("SELECT NOPE(1)")
        db.fails("SELECT LOWER()")
        db.fails("SELECT LOWER('a', 'b')")
        db.fails("SELECT COALESCE()")
    }

    @Test
    fun `literals`() {
        assertEquals("it's", scalar("'it''s'"))
        assertEquals("", scalar("''"))
        assertEquals(bytes(10, 11), scalar("x'0a0B'"))
        assertEquals(bytes(), scalar("x''"))
        assertEquals(1L, scalar("TRUE"))
        assertEquals(0L, scalar("FALSE"))
        assertNull(scalar("NULL"))
        assertEquals("ünï\n", scalar("'ünï\n'"))
    }

    @Test
    fun `comments are ignored`() {
        assertEquals(3L, db.scalar("SELECT 1 -- one\n + 2"))
        assertEquals(1L, db.scalar("/* lead */ SELECT /* mid */ 1"))
        assertEquals("--", db.scalar("SELECT '--'"))
    }

    @Test
    fun `parameters`() {
        assertEquals(5L, scalar("? + ?", 2, 3L))
        assertNull(scalar("?", null))
        assertEquals("text", scalar("?", "text"))
        assertEquals(bytes(1, 2), scalar("?", byteArrayOf(1, 2)))
        assertEquals(1L, scalar("?", true))
        assertEquals(0L, scalar("?", false))
        assertEquals(1.5, scalar("?", 1.5f))
        assertEquals(7L, scalar("?", 7.toShort()))
        assertEquals(7L, scalar("?", 7.toByte()))
        db.fails("SELECT ?")
        db.fails("SELECT ?", 1, 2)
        db.fails("SELECT ?", Any())
        db.fails("SELECT ?", Double.NaN)
    }

    @Test
    fun `result columns are named after aliases or source text`() {
        val result = db.execute("SELECT 1 + 1, 'x' AS y, 2 AS z")
        assertEquals(listOf("1 + 1", "y", "z"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0][0])
        assertEquals("x", result.rows[0]["y"])
        assertEquals(2L, result.rows[0]["Z"])
        assertFailsWith<IllegalArgumentException> { result.rows[0]["missing"] }
    }

    @Test
    fun `a table free select honours where and limit`() {
        assertEquals(1, db.rows("SELECT 1 WHERE 1 = 1").size)
        assertEquals(0, db.rows("SELECT 1 WHERE 1 = 2").size)
        assertEquals(0, db.rows("SELECT 1 WHERE NULL").size)
        assertEquals(0, db.rows("SELECT 1 LIMIT 0").size)
        assertEquals(0, db.rows("SELECT 1 LIMIT 5 OFFSET 1").size)
        assertEquals(listOf(listOf<Any?>(2L)), db.rows("SELECT COUNT(*) + 1"))
    }

    @Test
    fun `selecting a column without a table is an error`() {
        db.fails("SELECT a")
        db.fails("SELECT *")
    }
}
