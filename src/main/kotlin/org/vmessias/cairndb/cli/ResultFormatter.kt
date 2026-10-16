package org.vmessias.cairndb.cli

import org.vmessias.cairndb.sql.SqlResult
import java.util.HexFormat

internal enum class OutputMode(val label: String) {
    TABLE("table"),
    LIST("list"),
    CSV("csv"),
    JSON("json"),
    ;

    companion object {
        fun parse(name: String): OutputMode? = entries.firstOrNull { it.label.equals(name, ignoreCase = true) }
    }
}

/** Renders the rows of a [SqlResult]; a result without columns renders as nothing. */
internal object ResultFormatter {
    fun format(result: SqlResult, mode: OutputMode, headers: Boolean): String {
        if (result.columns.isEmpty()) return ""
        return when (mode) {
            OutputMode.TABLE -> table(result, headers)
            OutputMode.LIST -> list(result, headers)
            OutputMode.CSV -> csv(result, headers)
            OutputMode.JSON -> json(result)
        }
    }

    /** NULL as `NULL`, blobs as `x'cafe'`, everything else as its natural text. */
    fun display(value: Any?): String = when (value) {
        null -> "NULL"
        is ByteArray -> blob(value)
        else -> value.toString()
    }

    private fun blob(bytes: ByteArray): String = "x'" + HexFormat.of().formatHex(bytes) + "'"

    // ---- table

    private fun table(result: SqlResult, headers: Boolean): String {
        val columns = result.columns
        val rows = result.rows.map { row -> row.values.map { escapeControl(display(it)) } }
        val widths = IntArray(columns.size) { column ->
            maxOf(if (headers) width(columns[column]) else 0, rows.maxOfOrNull { width(it[column]) } ?: 0, 1)
        }
        val rule = widths.joinToString("+", "+", "+") { "-".repeat(it + 2) }
        return buildString {
            append(rule).append('\n')
            if (headers) {
                append(line(columns, widths, rightAligned = BooleanArray(columns.size))).append('\n')
                append(rule).append('\n')
            }
            result.rows.forEachIndexed { index, row ->
                val numeric = BooleanArray(columns.size) { row.values[it] is Long || row.values[it] is Double }
                append(line(rows[index], widths, numeric)).append('\n')
            }
            if (result.rows.isNotEmpty() || !headers) append(rule).append('\n')
        }
    }

    private fun line(cells: List<String>, widths: IntArray, rightAligned: BooleanArray): String =
        cells.indices.joinToString("|", "|", "|") { column ->
            val padding = " ".repeat(widths[column] - width(cells[column]))
            if (rightAligned[column]) " $padding${cells[column]} " else " ${cells[column]}$padding "
        }

    private fun width(text: String): Int = text.codePointCount(0, text.length)

    private fun escapeControl(text: String): String {
        if (text.none { it < ' ' }) return text
        return buildString {
            for (c in text) {
                when {
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }

    // ---- list

    private fun list(result: SqlResult, headers: Boolean): String = buildString {
        if (headers) append(result.columns.joinToString("|")).append('\n')
        for (row in result.rows) {
            append(row.values.joinToString("|") { if (it == null) "" else display(it) }).append('\n')
        }
    }

    // ---- csv

    private fun csv(result: SqlResult, headers: Boolean): String = buildString {
        if (headers) append(result.columns.joinToString(",") { csvField(it) }).append('\n')
        for (row in result.rows) {
            append(row.values.joinToString(",") { if (it == null) "" else csvField(display(it)) }).append('\n')
        }
    }

    /** An empty string is quoted so that it stays distinguishable from NULL, which is an empty field. */
    private fun csvField(text: String): String =
        if (text.isEmpty() || text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            '"' + text.replace("\"", "\"\"") + '"'
        } else {
            text
        }

    // ---- json

    private fun json(result: SqlResult): String {
        if (result.rows.isEmpty()) return "[]\n"
        return buildString {
            append("[\n")
            result.rows.forEachIndexed { index, row ->
                append(result.columns.indices.joinToString(",", "{", "}") { column ->
                    jsonString(result.columns[column]) + ":" + jsonValue(row.values[column])
                })
                append(if (index < result.rows.lastIndex) ",\n" else "\n")
            }
            append("]\n")
        }
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Long -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else jsonString(value.toString())
        is ByteArray -> jsonString(HexFormat.of().formatHex(value))
        else -> jsonString(value.toString())
    }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
