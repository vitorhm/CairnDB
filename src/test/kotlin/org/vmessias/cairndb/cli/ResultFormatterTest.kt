package org.vmessias.cairndb.cli

import org.vmessias.cairndb.sql.Row
import org.vmessias.cairndb.sql.SqlResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultFormatterTest {
    private fun result(columns: List<String>, vararg rows: List<Any?>) =
        SqlResult(columns, rows.map { Row(columns, it) })

    private val sample = result(
        listOf("id", "name", "score", "data"),
        listOf(1L, "Ana", 1.5, byteArrayOf(0x0a, -1)),
        listOf(22L, null, -3.0, null),
        listOf(3L, "a,\"b\"\nc", 0.0, byteArrayOf()),
        listOf(4L, "", 1e21, null),
    )

    private fun format(result: SqlResult, mode: OutputMode, headers: Boolean = true) = ResultFormatter.format(result, mode, headers)

    @Test
    fun `table aligns numbers right and text left`() {
        val expected = """
            +----+----------+--------+---------+
            | id | name     | score  | data    |
            +----+----------+--------+---------+
            |  1 | Ana      |    1.5 | x'0aff' |
            | 22 | NULL     |   -3.0 | NULL    |
            |  3 | a,"b"\nc |    0.0 | x''     |
            |  4 |          | 1.0E21 | NULL    |
            +----+----------+--------+---------+

        """.trimIndent()
        assertEquals(expected, format(sample, OutputMode.TABLE))
    }

    @Test
    fun `table without headers has no header rows`() {
        val one = result(listOf("a"), listOf(1L), listOf(20L))
        assertEquals("+----+\n|  1 |\n| 20 |\n+----+\n", format(one, OutputMode.TABLE, headers = false))
    }

    @Test
    fun `table with no rows shows only the header`() {
        assertEquals("+-----+\n| abc |\n+-----+\n", format(result(listOf("abc")), OutputMode.TABLE))
    }

    @Test
    fun `table width counts characters, not UTF-16 units`() {
        val text = result(listOf("s"), listOf("é😀"), listOf("abc"))
        assertEquals("+-----+\n| s   |\n+-----+\n| é😀  |\n| abc |\n+-----+\n", format(text, OutputMode.TABLE))
    }

    @Test
    fun `table escapes control characters`() {
        val text = result(listOf("s"), listOf("a\tb\u0001"))
        val rule = "+" + "-".repeat(12) + "+\n"
        assertEquals(rule + "| s" + " ".repeat(10) + "|\n" + rule + "| a\\tb\\u0001 |\n" + rule, format(text, OutputMode.TABLE))
    }

    @Test
    fun `list separates with a bar and prints NULL as nothing`() {
        val expected = "id|name|score|data\n1|Ana|1.5|x'0aff'\n22||-3.0|\n3|a,\"b\"\nc|0.0|x''\n4||1.0E21|\n"
        assertEquals(expected, format(sample, OutputMode.LIST))
        assertEquals(expected.substringAfter('\n'), format(sample, OutputMode.LIST, headers = false))
    }

    @Test
    fun `csv quotes where needed and tells an empty string from NULL`() {
        val expected = "id,name,score,data\n1,Ana,1.5,x'0aff'\n22,,-3.0,\n3,\"a,\"\"b\"\"\nc\",0.0,x''\n4,\"\",1.0E21,\n"
        assertEquals(expected, format(sample, OutputMode.CSV))
        assertEquals(expected.substringAfter('\n'), format(sample, OutputMode.CSV, headers = false))
    }

    @Test
    fun `json is an array of objects`() {
        val expected = """
            [
            {"id":1,"name":"Ana","score":1.5,"data":"0aff"},
            {"id":22,"name":null,"score":-3.0,"data":null},
            {"id":3,"name":"a,\"b\"\nc","score":0.0,"data":""},
            {"id":4,"name":"","score":1.0E21,"data":null}
            ]

        """.trimIndent()
        assertEquals(expected, format(sample, OutputMode.JSON))
    }

    @Test
    fun `json escapes and handles empty results`() {
        assertEquals("[]\n", format(result(listOf("a")), OutputMode.JSON))
        val odd = result(listOf("we\"ird"), listOf("back\\slash\u0001\r"), listOf(Double.POSITIVE_INFINITY))
        assertEquals("[\n{\"we\\\"ird\":\"back\\\\slash\\u0001\\r\"},\n{\"we\\\"ird\":\"Infinity\"}\n]\n", format(odd, OutputMode.JSON))
    }

    @Test
    fun `a result without columns prints nothing`() {
        for (mode in OutputMode.entries) assertEquals("", format(SqlResult(emptyList(), emptyList()), mode))
    }

    @Test
    fun `modes are found by name`() {
        assertEquals(OutputMode.CSV, OutputMode.parse("CSV"))
        assertEquals(OutputMode.TABLE, OutputMode.parse("table"))
        assertEquals(null, OutputMode.parse("xml"))
    }
}
