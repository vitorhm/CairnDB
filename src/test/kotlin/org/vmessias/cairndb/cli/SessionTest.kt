package org.vmessias.cairndb.cli

import org.vmessias.cairndb.DatabaseException
import org.vmessias.cairndb.DatabaseTestBase
import org.vmessias.cairndb.sql.SqlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest : DatabaseTestBase() {
    private val db by lazy { open() }

    @Test
    fun `outside a transaction statements run directly and commit on their own`() {
        val session = Session(db)
        session.run { it.execute("CREATE TABLE t (a INTEGER)") }
        session.run { it.execute("INSERT INTO t VALUES (1)") }
        assertFalse(session.inTransaction)
        assertEquals(1L, db.execute("SELECT count(*) FROM t").rows.single()[0])
    }

    @Test
    fun `commit makes the changes of the transaction visible to everybody`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        val session = Session(db)
        session.begin()
        assertTrue(session.inTransaction)
        session.run { it.execute("INSERT INTO t VALUES (1), (2)") }
        assertEquals(2L, session.run { it.execute("SELECT count(*) FROM t").rows.single()[0] })
        session.commit()
        assertFalse(session.inTransaction)
        assertEquals(2L, db.execute("SELECT count(*) FROM t").rows.single()[0])
    }

    @Test
    fun `rollback discards them`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        db.execute("INSERT INTO t VALUES (7)")
        val session = Session(db)
        session.begin()
        session.run { it.execute("DELETE FROM t") }
        session.run { it.execute("CREATE TABLE u (a INTEGER)") }
        session.rollback()
        assertEquals(listOf(7L), db.execute("SELECT a FROM t").rows.map { it[0] })
        assertEquals(listOf("t"), db.tables().map { it.name })
    }

    @Test
    fun `an error inside the transaction reaches the caller and dooms the commit`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        val session = Session(db)
        session.begin()
        session.run { it.execute("INSERT INTO t VALUES (1)") }
        assertFailsWith<SqlException> { session.run { it.execute("INSERT INTO missing VALUES (1)") } }
        assertTrue(session.inTransaction)
        assertFailsWith<DatabaseException> { session.commit() }
        assertFalse(session.inTransaction)
        assertEquals(0L, db.execute("SELECT count(*) FROM t").rows.single()[0])
    }

    @Test
    fun `a doomed transaction can still be rolled back`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        val session = Session(db)
        session.begin()
        assertFailsWith<SqlException> { session.run { it.execute("INSERT INTO missing VALUES (1)") } }
        session.rollback()
        assertEquals(0L, db.execute("SELECT count(*) FROM t").rows.single()[0])
        db.execute("INSERT INTO t VALUES (1)")
    }

    @Test
    fun `misuse is reported`() {
        val session = Session(db)
        assertFailsWith<CliException> { session.commit() }
        assertFailsWith<CliException> { session.rollback() }
        session.begin()
        assertFailsWith<CliException> { session.begin() }
        session.rollback()
    }

    @Test
    fun `closing rolls back an open transaction and frees the lock`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        val session = Session(db)
        session.begin()
        session.run { it.execute("INSERT INTO t VALUES (1)") }
        session.close()
        assertFalse(session.inTransaction)
        assertEquals(0L, db.execute("SELECT count(*) FROM t").rows.single()[0])
    }

    @Test
    fun `a transaction can be opened again after it ended`() {
        db.execute("CREATE TABLE t (a INTEGER)")
        val session = Session(db)
        repeat(3) { round ->
            session.begin()
            session.run { it.execute("INSERT INTO t VALUES (?)", round) }
            if (round == 1) session.rollback() else session.commit()
        }
        assertEquals(listOf(0L, 2L), db.execute("SELECT a FROM t ORDER BY a").rows.map { it[0] })
    }
}
