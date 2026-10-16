package org.vmessias.cairndb.cli

import org.vmessias.cairndb.Database
import org.vmessias.cairndb.sql.TableInfo
import org.vmessias.cairndb.sql.quoteIdentifier
import java.util.HexFormat

/** Writes SQL that recreates tables and their rows; the output can be fed back to the shell. */
internal object Dumper {
    fun dump(db: Database, tables: List<TableInfo>, out: Appendable) {
        db.read {
            out.append("BEGIN;\n")
            for (table in tables) {
                out.append(table.createStatement()).append(";\n")
                val prefix = "INSERT INTO ${quoteIdentifier(table.name)} (" +
                    table.columns.joinToString(", ") { quoteIdentifier(it.name) } + ") VALUES ("
                for (row in db.execute("SELECT * FROM ${quoteIdentifier(table.name)}").rows) {
                    out.append(prefix).append(row.values.joinToString(", ") { literal(it) }).append(");\n")
                }
            }
            out.append("COMMIT;\n")
        }
    }

    fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        // The parser has no literal for the smallest Long, so it is written as an expression.
        is Long -> if (value == Long.MIN_VALUE) "(-9223372036854775807 - 1)" else value.toString()
        is Double -> when {
            value == Double.POSITIVE_INFINITY -> "(1e308 * 10)"
            value == Double.NEGATIVE_INFINITY -> "(-1e308 * 10)"
            else -> value.toString()
        }
        is String -> "'" + value.replace("'", "''") + "'"
        is ByteArray -> "x'" + HexFormat.of().formatHex(value) + "'"
        else -> throw IllegalArgumentException("Unexpected value of type ${value::class.simpleName}")
    }
}
