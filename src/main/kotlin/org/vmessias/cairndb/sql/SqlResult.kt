package org.vmessias.cairndb.sql

/**
 * One row of a [SqlResult]. Values are `null` (SQL NULL), [Long] (INTEGER), [Double] (REAL),
 * [String] (TEXT) or [ByteArray] (BLOB).
 */
public class Row internal constructor(private val columns: List<String>, public val values: List<Any?>) {
    public operator fun get(index: Int): Any? = values[index]

    /** Value of the column called [column] (case-insensitive); the first one if several share the name. */
    public operator fun get(column: String): Any? {
        val index = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        require(index >= 0) { "No column named '$column' in $columns" }
        return values[index]
    }

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]") {
        when (it) {
            null -> "NULL"
            is String -> "'$it'"
            is ByteArray -> "x'" + it.joinToString("") { b -> "%02x".format(b) } + "'"
            else -> it.toString()
        }
    }
}

/**
 * The outcome of [org.vmessias.cairndb.Database.execute].
 *
 * @property columns names of the result columns; empty for statements that return no rows.
 * @property rows the whole result, already materialised in memory.
 * @property rowsAffected rows inserted, updated or deleted; 0 for queries and DDL.
 * @property lastInsertId the row id (or integer primary key) given to the last row inserted by an
 *   INSERT into a table that assigns ids itself; null otherwise.
 */
public class SqlResult internal constructor(
    public val columns: List<String>,
    public val rows: List<Row>,
    public val rowsAffected: Int = 0,
    public val lastInsertId: Long? = null,
) {
    override fun toString(): String = "SqlResult(columns=$columns, rows=${rows.size}, rowsAffected=$rowsAffected)"
}
