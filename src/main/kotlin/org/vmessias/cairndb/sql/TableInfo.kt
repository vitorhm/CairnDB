package org.vmessias.cairndb.sql

/** A column of a [TableInfo]. [type] is `INTEGER`, `REAL`, `TEXT` or `BLOB`. */
public class ColumnInfo internal constructor(
    public val name: String,
    public val type: String,
    public val notNull: Boolean,
    public val primaryKey: Boolean,
)

/**
 * The definition of a table, as returned by [org.vmessias.cairndb.Database.tables].
 *
 * @property primaryKey names of the primary key columns in key order; empty if the table has none
 *   (rows are then identified by a hidden row id).
 */
public class TableInfo internal constructor(
    public val name: String,
    public val columns: List<ColumnInfo>,
    public val primaryKey: List<String>,
) {
    /** A `CREATE TABLE` statement, without the trailing semicolon, that recreates this definition. */
    public fun createStatement(): String = buildString {
        append("CREATE TABLE ").append(quoteIdentifier(name)).append(" (")
        val inlineKey = primaryKey.size == 1
        val parts = columns.map { column ->
            buildString {
                append(quoteIdentifier(column.name)).append(' ').append(column.type)
                if (inlineKey && column.primaryKey) append(" PRIMARY KEY") else if (column.notNull) append(" NOT NULL")
            }
        }.toMutableList()
        if (primaryKey.size > 1) parts += primaryKey.joinToString(", ", "PRIMARY KEY (", ")") { quoteIdentifier(it) }
        parts.forEachIndexed { index, part ->
            append("\n  ").append(part)
            if (index < parts.lastIndex) append(',')
        }
        append("\n)")
    }

    override fun toString(): String = createStatement()

    internal companion object {
        fun of(schema: TableSchema): TableInfo = TableInfo(
            schema.name,
            schema.columns.mapIndexed { index, column ->
                ColumnInfo(column.name, column.type.name, column.notNull, index in schema.primaryKey)
            },
            schema.primaryKey.map { schema.columns[it].name },
        )
    }
}

/** Words that are not reserved in the grammar but would confuse a reader, or the parser, as a bare name. */
private val QUOTED_TOO = setOf("IF", "EXISTS", "KEY")

/** [name] as it must be written in SQL: bare when it is a plain, non-reserved word, double-quoted otherwise. */
internal fun quoteIdentifier(name: String): String {
    val plain = name.isNotEmpty() &&
        (name[0].isLetter() || name[0] == '_') &&
        name.all { it.isLetterOrDigit() || it == '_' } &&
        name.uppercase() !in RESERVED_WORDS &&
        name.uppercase() !in QUOTED_TOO
    return if (plain) name else '"' + name.replace("\"", "\"\"") + '"'
}
