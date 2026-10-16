package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableInfoTest : SqlTestBase() {
    private val db by lazy { open() }

    @Test
    fun `an empty database has no tables`() {
        assertEquals(emptyList(), db.tables())
        assertNull(db.table("t"))
    }

    @Test
    fun `tables are listed by lower-cased name and keep their case`() {
        db.execute("CREATE TABLE Zeta (a INTEGER)")
        db.execute("CREATE TABLE alpha (a INTEGER)")
        db.execute("CREATE TABLE \"Mid Table\" (a INTEGER)")
        assertEquals(listOf("alpha", "Mid Table", "Zeta"), db.tables().map { it.name })
        assertEquals("Zeta", db.table("zETA")?.name)
        db.execute("DROP TABLE alpha")
        assertEquals(listOf("Mid Table", "Zeta"), db.tables().map { it.name })
    }

    @Test
    fun `columns, types and keys are described`() {
        db.execute("CREATE TABLE t (id INT, name VARCHAR NOT NULL, ratio DOUBLE PRECISION, data BLOB, PRIMARY KEY (name, id))")
        val table = db.table("t")!!
        assertEquals(listOf("name", "id"), table.primaryKey)
        assertEquals(
            listOf("id:INTEGER:true:true", "name:TEXT:true:true", "ratio:REAL:false:false", "data:BLOB:false:false"),
            table.columns.map { "${it.name}:${it.type}:${it.notNull}:${it.primaryKey}" },
        )
    }

    @Test
    fun `a table without a primary key has an empty key`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        assertEquals(emptyList(), db.table("t")!!.primaryKey)
    }

    @Test
    fun `the create statement is formatted per column`() {
        db.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        db.execute("CREATE TABLE pairs (a INTEGER, b TEXT, PRIMARY KEY (a, b))")
        db.execute("CREATE TABLE loose (a REAL)")
        assertEquals("CREATE TABLE people (\n  id INTEGER PRIMARY KEY,\n  name TEXT NOT NULL,\n  age INTEGER\n)", db.table("people")!!.createStatement())
        assertEquals("CREATE TABLE pairs (\n  a INTEGER NOT NULL,\n  b TEXT NOT NULL,\n  PRIMARY KEY (a, b)\n)", db.table("pairs")!!.createStatement())
        assertEquals("CREATE TABLE loose (\n  a REAL\n)", db.table("loose")!!.createStatement())
    }

    @Test
    fun `names that are not plain words are quoted`() {
        assertEquals("plain_name1", quoteIdentifier("plain_name1"))
        assertEquals("\"select\"", quoteIdentifier("select"))
        assertEquals("\"Order\"", quoteIdentifier("Order"))
        assertEquals("\"if\"", quoteIdentifier("if"))
        assertEquals("\"key\"", quoteIdentifier("key"))
        assertEquals("\"two words\"", quoteIdentifier("two words"))
        assertEquals("\"1st\"", quoteIdentifier("1st"))
        assertEquals("\"say \"\"hi\"\"\"", quoteIdentifier("say \"hi\""))
        assertEquals("é_1", quoteIdentifier("é_1"))
    }

    @Test
    fun `the create statement recreates an identical table`() {
        val definitions = listOf(
            "CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT NOT NULL, data BLOB, r REAL)",
            "CREATE TABLE \"Order\" (\"select\" INTEGER, \"my \"\"col\"\"\" TEXT, key REAL NOT NULL, PRIMARY KEY (\"select\", \"my \"\"col\"\"\"))",
            "CREATE TABLE \"if\" (\"exists\" INTEGER, \"a b\" TEXT NOT NULL)",
            "CREATE TABLE ünï (ç INTEGER PRIMARY KEY)",
        )
        definitions.forEach { db.execute(it) }
        val original = db.tables()
        db.close()
        val other = Database.open(dir.resolve("other.db"))
        other.use {
            original.forEach { table -> it.execute(table.createStatement()) }
            val copy = it.tables()
            assertEquals(original.map { t -> t.createStatement() }, copy.map { t -> t.createStatement() })
            assertEquals(original.map { t -> t.primaryKey }, copy.map { t -> t.primaryKey })
            assertTrue(copy.size == definitions.size)
        }
    }
}
