package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Database
import org.vmessias.cairndb.DatabaseTestBase

/** Helpers for SQL tests: rows come back as plain lists, with BLOBs turned into lists of bytes so they compare by content. */
abstract class SqlTestBase : DatabaseTestBase() {
    protected fun plain(value: Any?): Any? = if (value is ByteArray) value.toList() else value

    protected fun Database.rows(sql: String, vararg params: Any?): List<List<Any?>> =
        execute(sql, *params).rows.map { row -> row.values.map(::plain) }

    protected fun Database.scalar(sql: String, vararg params: Any?): Any? = rows(sql, *params).single().single()

    protected fun Database.column(sql: String, vararg params: Any?): List<Any?> = rows(sql, *params).map { it.single() }

    protected fun Database.update(sql: String, vararg params: Any?): Int = execute(sql, *params).rowsAffected

    protected fun Database.fails(sql: String, vararg params: Any?): SqlException =
        kotlin.test.assertFailsWith<SqlException>(sql) { execute(sql, *params) }

    protected fun bytes(vararg values: Int): List<Byte> = values.map { it.toByte() }
}
