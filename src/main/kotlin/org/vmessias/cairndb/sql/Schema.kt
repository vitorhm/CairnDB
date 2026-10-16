package org.vmessias.cairndb.sql

import org.vmessias.cairndb.CorruptDatabaseException
import java.util.Locale

internal const val SCHEMA_BUCKET = "sql.schema"
internal const val TABLE_BUCKET_PREFIX = "sql.table."

internal enum class ColumnType(val tag: Int) {
    INTEGER(1),
    REAL(2),
    TEXT(3),
    BLOB(4),
    ;

    companion object {
        fun ofTag(tag: Int): ColumnType = entries.firstOrNull { it.tag == tag }
            ?: throw CorruptDatabaseException("Unknown column type tag $tag")
    }
}

internal class Column(val name: String, val type: ColumnType, val notNull: Boolean)

/**
 * Definition of a table as stored in the schema bucket.
 *
 * Rows live in the bucket [bucketName], keyed by the order-preserving encoding of the primary key
 * (see [KeyCodec]) or, for tables without one, by a hidden row id taken from [nextRowId].
 */
internal class TableSchema(
    val name: String,
    val columns: List<Column>,
    /** Indexes into [columns] of the primary key columns, in key order; empty for a hidden row id. */
    val primaryKey: List<Int>,
    var nextRowId: Long = 1,
) {
    private val byName: Map<String, Int> =
        columns.withIndex().associate { (index, column) -> column.name.lowercase(Locale.ROOT) to index }

    val bucketName: String get() = TABLE_BUCKET_PREFIX + name.lowercase(Locale.ROOT)

    val hasHiddenRowId: Boolean get() = primaryKey.isEmpty()

    /** The single INTEGER primary key column, whose value is assigned automatically when omitted. */
    val autoKeyColumn: Int?
        get() = primaryKey.singleOrNull()?.takeIf { columns[it].type == ColumnType.INTEGER }

    fun indexOf(columnName: String): Int? = byName[columnName.lowercase(Locale.ROOT)]
}

internal object SchemaCodec {
    private const val VERSION = 1

    fun encode(schema: TableSchema): ByteArray {
        val out = ByteSink()
        out.u8(VERSION)
        out.string(schema.name)
        out.varInt(schema.nextRowId)
        out.varUInt(schema.columns.size.toLong())
        for (column in schema.columns) {
            out.string(column.name)
            out.u8(column.type.tag)
            out.u8(if (column.notNull) 1 else 0)
        }
        out.varUInt(schema.primaryKey.size.toLong())
        for (index in schema.primaryKey) out.varUInt(index.toLong())
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): TableSchema {
        val input = ByteSource(bytes)
        val version = input.u8()
        if (version != VERSION) throw CorruptDatabaseException("Unsupported SQL schema version $version")
        val name = input.string()
        val nextRowId = input.varInt()
        val columnCount = input.varUInt()
        if (columnCount !in 1..input.remaining) throw CorruptDatabaseException("Malformed SQL schema")
        val columns = List(columnCount.toInt()) {
            Column(input.string(), ColumnType.ofTag(input.u8()), input.u8() != 0)
        }
        val keyCount = input.varUInt()
        if (keyCount !in 0..columns.size) throw CorruptDatabaseException("Malformed SQL schema")
        val primaryKey = List(keyCount.toInt()) {
            val index = input.varUInt()
            if (index !in 0 until columns.size) throw CorruptDatabaseException("Malformed SQL schema")
            index.toInt()
        }
        return TableSchema(name, columns, primaryKey, nextRowId)
    }
}
