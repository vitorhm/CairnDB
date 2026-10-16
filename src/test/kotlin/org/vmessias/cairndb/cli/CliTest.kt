package org.vmessias.cairndb.cli

import org.vmessias.cairndb.Database
import org.vmessias.cairndb.DatabaseTestBase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest : DatabaseTestBase() {
    private class Outcome(val status: Int, val out: String, val err: String)

    /** Runs the CLI in-process against [path]; output is normalised to `\n` line ends. */
    private fun cliOn(path: Path, args: List<String>, stdin: String, console: Boolean): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = Cli.run(
            listOf(path.toString(), "--no-sync") + args,
            ByteArrayInputStream(stdin.toByteArray()),
            Charsets.UTF_8,
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
            console,
        )
        return Outcome(status, out.toString(Charsets.UTF_8).replace("\r\n", "\n"), err.toString(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    private fun run(vararg args: String, stdin: String = "", console: Boolean = false) = cliOn(file, args.toList(), stdin, console)

    private fun script(text: String, vararg args: String) = run(*args, stdin = text.trimIndent() + "\n")

    private fun seed() {
        val r = run("-c", "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT); INSERT INTO t (name) VALUES ('a'), ('bb')")
        assertEquals(0, r.status, r.err)
    }

    private fun count(sql: String = "SELECT count(*) FROM t"): Long =
        Database.open(file).use { (it.execute(sql).rows.single()[0] as Long) }

    // ---- command line

    @Test
    fun `help and version`() {
        val help = run("--help")
        assertEquals(0, help.status)
        assertTrue(help.out.startsWith("Usage: cairndb [options] FILE [SQL...]"), help.out)
        val version = cliOn(file, listOf("--version"), "", false)
        assertEquals(0, version.status)
        assertTrue(version.out.startsWith("cairndb "), version.out)
    }

    @Test
    fun `usage errors exit with status 2`() {
        val none = Cli.run(emptyList(), ByteArrayInputStream(ByteArray(0)), Charsets.UTF_8, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), false)
        assertEquals(2, none)
        val unknown = run("--nope")
        assertEquals(2, unknown.status)
        assertTrue(unknown.err.contains("Unknown option --nope"), unknown.err)
        val pageSize = run("--page-size", "1000")
        assertEquals(2, pageSize.status)
        assertTrue(pageSize.err.contains("pageSize must be a power of two"), pageSize.err)
    }

    @Test
    fun `the page size applies to a new database`() {
        assertEquals(0, run("--page-size", "512", "-c", "CREATE TABLE t (a INTEGER)").status)
        Database.open(file).use { assertEquals(64, it.maxKeySize) }
    }

    @Test
    fun `a database that cannot be opened exits with status 1`() {
        val db = open()
        val locked = run("-c", "SELECT 1")
        assertEquals(1, locked.status)
        assertTrue(locked.err.startsWith("cairndb: cannot open "), locked.err)
        db.close()
        Files.write(dir.resolve("junk.db"), ByteArray(5000) { it.toByte() })
        val junk = cliOn(dir.resolve("junk.db"), listOf("-c", "SELECT 1"), "", false)
        assertEquals(1, junk.status)
        assertTrue(junk.err.startsWith("cairndb: cannot open "), junk.err)
    }

    // ---- running SQL

    @Test
    fun `runs commands and prints tables`() {
        val r = run("-c", "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT); INSERT INTO t (name) VALUES ('a'), ('bb'); SELECT * FROM t")
        assertEquals(0, r.status, r.err)
        val expected = "OK\n2 rows affected (last id 2)\n" +
            "+----+------+\n| id | name |\n+----+------+\n|  1 | a    |\n|  2 | bb   |\n+----+------+\n(2 rows)\n"
        assertEquals(expected, r.out)
        assertEquals("", r.err)
    }

    @Test
    fun `changes persist between runs`() {
        seed()
        val r = run("-c", "UPDATE t SET name = 'z' WHERE id = 1; DELETE FROM t WHERE id = 2; SELECT name FROM t")
        assertEquals("1 row affected\n1 row affected\n+------+\n| name |\n+------+\n| z    |\n+------+\n(1 row)\n", r.out)
        assertEquals("+---+\n| n |\n+---+\n| 1 |\n+---+\n(1 row)\n", run("-c", "SELECT count(*) AS n FROM t").out)
    }

    @Test
    fun `SQL can be given as extra arguments`() {
        seed()
        assertEquals("1\n", run("--mode", "list", "--no-headers", "SELECT count(*) - 1 FROM t").out)
    }

    @Test
    fun `output modes`() {
        seed()
        run("-c", "INSERT INTO t (name) VALUES (NULL), ('x,\"y\"')")
        val query = "SELECT * FROM t ORDER BY id"
        assertEquals("id|name\n1|a\n2|bb\n3|\n4|x,\"y\"\n", run("--mode", "list", "-c", query).out)
        assertEquals("id,name\n1,a\n2,bb\n3,\n4,\"x,\"\"y\"\"\"\n", run("--mode=csv", "-c", query).out)
        assertEquals("1,a\n2,bb\n3,\n4,\"x,\"\"y\"\"\"\n", run("-m", "csv", "--no-headers", "-c", query).out)
        assertEquals(
            "[\n{\"id\":1,\"name\":\"a\"},\n{\"id\":2,\"name\":\"bb\"},\n{\"id\":3,\"name\":null},\n{\"id\":4,\"name\":\"x,\\\"y\\\"\"}\n]\n",
            run("--mode", "json", "-c", query).out,
        )
        // Only the table mode comments on statements that return no rows.
        assertEquals("", run("--mode", "csv", "-c", "UPDATE t SET name = 'q'").out)
    }

    @Test
    fun `a script from standard input reports errors and carries on`() {
        val r = script(
            """
            -- setup
            CREATE TABLE t (a INTEGER);
            INSERT INTO t VALUES (1),
                (2);
            SELEC 1;
            INSERT INTO t VALUES ('x');
            INSERT INTO t
            VALUS (1);
            SELECT sum(a) AS s FROM t
            """,
        )
        assertEquals(1, r.status)
        assertEquals("OK\n2 rows affected (last id 2)\n+---+\n| s |\n+---+\n| 3 |\n+---+\n(1 row)\n", r.out)
        val errors = r.err.lines()
        assertEquals(
            "stdin:5: Error: Syntax error at position 0: Expected a statement (CREATE, DROP, INSERT, SELECT, UPDATE, DELETE or EXPLAIN) but found 'SELEC'",
            errors[0],
        )
        assertEquals(listOf("  SELEC 1", "  ^"), errors.subList(1, 3))
        assertTrue(errors[3].startsWith("stdin:6: Error: ") && errors[3].contains("t.a"), errors[3])
        assertTrue(errors[4].startsWith("stdin:7: Error: Syntax error at position 14: "), errors[4])
        assertEquals(listOf("  VALUS (1)", "  ^", ""), errors.subList(5, 8))
    }

    @Test
    fun `bail stops at the first error`() {
        val r = script(
            """
            CREATE TABLE t (a INTEGER);
            INSERT INTO nope VALUES (1);
            INSERT INTO t VALUES (1);
            """,
            "--bail",
        )
        assertEquals(1, r.status)
        assertEquals("OK\n", r.out)
        assertEquals(1, r.err.lines().count { it.startsWith("stdin:2: Error: No such table: nope") })
        assertEquals(0, count())
    }

    @Test
    fun `errors in several commands give a failing status`() {
        val r = run("-c", "SELECT 1 AS one", "-c", "SELEC", "-c", "SELECT 2 AS two")
        assertEquals(1, r.status)
        assertTrue(r.out.contains("| one |") && r.out.contains("| two |"), r.out)
        assertTrue(r.err.startsWith("command:1: Error: "), r.err)
    }

    @Test
    fun `non ASCII text survives the round trip`() {
        val r = script("CREATE TABLE t (s TEXT);\nINSERT INTO t VALUES ('Zoë 😀 日本');\nSELECT s FROM t;", "--mode", "list", "--no-headers")
        assertEquals("Zoë 😀 日本\n", r.out)
    }

    @Test
    fun `a script file and nested reads`() {
        val inner = dir.resolve("my inner script.sql")
        Files.writeString(inner, "INSERT INTO t (name) VALUES ('from inner');\n")
        val outer = dir.resolve("outer.sql")
        Files.writeString(outer, "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT);\n.read \"$inner\"\nSELECT name FROM t;\n")
        val r = run("-f", outer.toString(), "--mode", "list", "--no-headers")
        assertEquals(0, r.status, r.err)
        assertEquals("from inner\n", r.out)
    }

    @Test
    fun `unreadable scripts are errors`() {
        val missing = dir.resolve("missing.sql")
        val r = run("-f", missing.toString())
        assertEquals(1, r.status)
        assertEquals("Error: Cannot read $missing\n", r.err)
        val viaDot = script(".read $missing\nSELECT 1 AS one;")
        assertEquals(1, viaDot.status)
        assertTrue(viaDot.err.startsWith("stdin:1: Error: Cannot read $missing"), viaDot.err)
        assertTrue(viaDot.out.contains("| one |"))
    }

    @Test
    fun `a script that reads itself is stopped`() {
        val loop = dir.resolve("loop.sql")
        Files.writeString(loop, "SELECT 1 AS one;\n.read $loop\n")
        val r = run("-f", loop.toString(), "--mode", "list", "--no-headers")
        assertEquals(1, r.status)
        assertTrue(r.err.contains("Too many nested .read commands"), r.err)
    }

    @Test
    fun `a script that is not UTF-8 is reported`() {
        val bad = dir.resolve("bad.sql")
        Files.write(bad, byteArrayOf('S'.code.toByte(), 0xC3.toByte(), 0x28, ';'.code.toByte(), '\n'.code.toByte()))
        val r = script(".read $bad")
        assertEquals(1, r.status)
        assertTrue(r.err.contains("is not valid UTF-8"), r.err)
    }

    @Test
    fun `standard input is ignored when commands are given unless interactive`() {
        assertEquals("1\n", run("--mode", "list", "--no-headers", "-c", "SELECT 1", stdin = "SELECT 2;").out)
        val both = run("--mode", "list", "--no-headers", "-c", "SELECT 1", "-i", stdin = "SELECT 2;\n")
        assertTrue(both.out.startsWith("cairndb "), both.out)
        assertTrue(both.out.endsWith("\n1\nsql> 2\nsql> \n"), both.out)
    }

    // ---- dot commands

    @Test
    fun `listing tables and schemas`() {
        val r = script(
            """
            CREATE TABLE zebra (id INTEGER PRIMARY KEY, "order" TEXT NOT NULL);
            CREATE TABLE Apple (a INTEGER, b REAL, PRIMARY KEY (a, b));
            .tables
            .schema Zebra
            .schema
            .schema nope
            """,
        )
        assertEquals(1, r.status)
        val zebra = "CREATE TABLE zebra (\n  id INTEGER PRIMARY KEY,\n  \"order\" TEXT NOT NULL\n);\n"
        val apple = "CREATE TABLE Apple (\n  a INTEGER NOT NULL,\n  b REAL NOT NULL,\n  PRIMARY KEY (a, b)\n);\n"
        assertEquals("OK\nOK\nApple\nzebra\n$zebra$apple$zebra", r.out)
        assertEquals("stdin:6: Error: No such table: nope\n", r.err)
    }

    @Test
    fun `settings can be shown and changed`() {
        seed()
        val r = script(
            """
            .mode
            .mode csv
            .mode
            .headers
            .headers off
            .headers
            SELECT * FROM t;
            .headers on
            SELECT name FROM t WHERE id = 1;
            .mode bogus
            .headers maybe
            """,
        )
        assertEquals("mode: table\nmode: csv\nheaders: on\nheaders: off\n1,a\n2,bb\nname\na\n", r.out)
        assertEquals(1, r.status)
        assertTrue(r.err.contains("stdin:10: Error: Unknown mode 'bogus'"), r.err)
        assertTrue(r.err.contains("stdin:11: Error: Expected on or off, not 'maybe'"), r.err)
    }

    @Test
    fun `timer help verify and unknown commands`() {
        val r = script(
            """
            .timer on
            SELECT 1 AS one;
            .verify
            .help
            .bogus
            """,
        )
        assertTrue(Regex("Run time: \\d+\\.\\d{3} s\n").containsMatchIn(r.out), r.out)
        assertTrue(r.out.contains("\nok\n") && r.out.contains(".dump [TABLE]"), r.out)
        assertEquals("stdin:5: Error: Unknown command: .bogus (try .help)\n", r.err)
    }

    @Test
    fun `quit stops everything that follows`() {
        val r = script("CREATE TABLE t (a INTEGER);\n.quit\nINSERT INTO t VALUES (1);")
        assertEquals(0, r.status)
        assertEquals(0, count())
        val viaCommand = run("-c", ".exit", "-c", "SELECT 1")
        assertEquals("", viaCommand.out)
    }

    @Test
    fun `dot commands are not recognised in the middle of a statement`() {
        val r = script("SELECT 1 AS a,\n.5 AS b;", "--mode", "list")
        assertEquals("a|b\n1|0.5\n", r.out)
    }

    @Test
    fun `dot commands work in -c too`() {
        seed()
        assertEquals("t\n", run("-c", ".tables").out)
    }

    // ---- transactions

    @Test
    fun `rollback discards what the transaction did`() {
        seed()
        val r = script(
            """
            BEGIN;
            INSERT INTO t (name) VALUES ('c');
            CREATE TABLE u (a INTEGER);
            SELECT count(*) AS n FROM t;
            ROLLBACK;
            SELECT count(*) AS n FROM t;
            """,
            "--mode", "list", "--no-headers",
        )
        assertEquals(0, r.status, r.err)
        assertEquals("3\n2\n", r.out)
        assertEquals(listOf("t"), Database.open(file).use { db -> db.tables().map { it.name } })
    }

    @Test
    fun `commit makes the transaction permanent`() {
        seed()
        val r = script("BEGIN;\nINSERT INTO t (name) VALUES ('c');\nDELETE FROM t WHERE id = 1;\nCOMMIT;")
        assertEquals("OK\n1 row affected (last id 3)\n1 row affected\nOK\n", r.out)
        assertEquals(0, r.status, r.err)
        assertEquals(2, count())
        assertEquals(1, count("SELECT count(*) FROM t WHERE name = 'c'"))
    }

    @Test
    fun `a transaction can span several -c arguments`() {
        seed()
        val r = run("-c", "BEGIN", "-c", "INSERT INTO t (name) VALUES ('c')", "-c", "COMMIT")
        assertEquals(0, r.status, r.err)
        assertEquals(3, count())
    }

    @Test
    fun `transaction keywords in other spellings`() {
        seed()
        val r = script(
            """
            /* go */ begin transaction;
            -- comment
            INSERT INTO t (name) VALUES ('c');
            END TRANSACTION;
            Begin;
            DELETE FROM t;
            rollback transaction
            """,
        )
        assertEquals(0, r.status, r.err)
        assertEquals(3, count())
    }

    @Test
    fun `quoted words are identifiers, not transaction commands`() {
        val r = script("CREATE TABLE \"begin\" (a INTEGER);\nSELECT * FROM \"begin\";")
        assertEquals(0, r.status, r.err)
        val bogus = script("BEGIN 5;\nCOMMIT now;")
        assertEquals(1, bogus.status)
        assertTrue(bogus.err.contains("Syntax error"), bogus.err)
    }

    @Test
    fun `transaction misuse is reported`() {
        val r = script("COMMIT;\nROLLBACK;\nBEGIN;\nBEGIN;\nCOMMIT;")
        assertEquals(1, r.status)
        assertEquals(
            "stdin:1: Error: No transaction is active\nstdin:2: Error: No transaction is active\n" +
                "stdin:4: Error: Cannot start a transaction within a transaction\n",
            r.err,
        )
    }

    @Test
    fun `an error dooms the transaction and the commit reports it`() {
        seed()
        val r = script(
            """
            BEGIN;
            INSERT INTO t (name) VALUES ('c');
            INSERT INTO nope VALUES (1);
            INSERT INTO t (name) VALUES ('d');
            COMMIT;
            SELECT count(*) AS n FROM t;
            """,
            "--mode", "list", "--no-headers",
        )
        assertEquals(1, r.status)
        assertEquals("2\n", r.out)
        val errors = r.err.lines()
        assertEquals("stdin:3: Error: No such table: nope", errors[0])
        assertEquals("stdin:4: Error: The transaction was aborted by an earlier error (use ROLLBACK to end the transaction)", errors[1])
        assertTrue(errors[2].startsWith("stdin:5: Error: Commit failed and the transaction was rolled back: "), errors[2])
    }

    @Test
    fun `a doomed transaction can be ended with rollback`() {
        seed()
        val r = script("BEGIN;\nDELETE FROM nope;\nROLLBACK;\nINSERT INTO t (name) VALUES ('c');")
        assertEquals(1, r.status)
        assertEquals(3, count())
        assertEquals(1, r.err.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `a transaction left open at the end is rolled back`() {
        seed()
        val r = script("BEGIN;\nINSERT INTO t (name) VALUES ('c');")
        assertEquals(1, r.status)
        assertTrue(r.err.contains("Warning: the transaction still open at the end of the input was rolled back"), r.err)
        assertEquals(2, count())
    }

    // ---- interactive sessions

    @Test
    fun `an interactive session shows prompts and keeps going after errors`() {
        val r = run(
            stdin = "CREATE TABLE t (a INTEGER);\nBEGIN;\nINSERT INTO t\n  VALUES (1);\nSELEC;\nCOMMIT;\n.quit\nINSERT INTO t VALUES (2);\n",
            console = true,
        )
        assertEquals(0, r.status)
        assertTrue(r.out.startsWith("cairndb "), r.out)
        assertTrue(r.out.contains("Connected to $file. Type .help for help, .quit to leave.\n"), r.out)
        assertTrue(r.out.endsWith("sql> OK\nsql> OK\nsql*> ...> 1 row affected (last id 1)\nsql*> sql*> OK\nsql> "), r.out)
        assertTrue(r.err.startsWith("Error: Syntax error at position 0"), r.err)
        assertEquals(1, count())
    }

    @Test
    fun `end of input in an interactive session`() {
        val r = run(stdin = "SELECT 1 AS one;\n", console = true)
        assertEquals(0, r.status)
        assertTrue(r.out.endsWith("(1 row)\nsql> \n"), r.out)
        val open = run(stdin = "BEGIN;\n", console = true)
        assertEquals(0, open.status)
        assertTrue(open.err.contains("Warning: the transaction still open"), open.err)
    }

    // ---- dump

    @Test
    fun `dump of an empty database`() {
        assertEquals("BEGIN;\nCOMMIT;\n", run("-c", ".dump").out)
    }

    @Test
    fun `dump of one table`() {
        seed()
        run("-c", "CREATE TABLE other (a INTEGER)")
        assertEquals(
            "BEGIN;\nCREATE TABLE t (\n  id INTEGER PRIMARY KEY,\n  name TEXT\n);\n" +
                "INSERT INTO t (id, name) VALUES (1, 'a');\nINSERT INTO t (id, name) VALUES (2, 'bb');\nCOMMIT;\n",
            run("-c", ".dump T").out,
        )
        assertEquals(1, run("-c", ".dump nope").status)
    }

    @Test
    fun `dump and restore reproduce every kind of value`() {
        val setup = run(
            "-c",
            """
            CREATE TABLE "Order" ("select" INTEGER, "my col" TEXT, key REAL, PRIMARY KEY ("select", "my col"));
            CREATE TABLE plain (a INTEGER, b BLOB, c TEXT, d REAL NOT NULL);
            CREATE TABLE auto (id INTEGER PRIMARY KEY, v TEXT);
            CREATE TABLE "if" (a INTEGER);
            CREATE TABLE empty (a INTEGER);
            INSERT INTO "Order" VALUES (1, 'x', 1.5), (1, 'it''s', NULL), (-9223372036854775807 - 1, 'min', 1e300), (9223372036854775807, '', -2.5e-7);
            INSERT INTO plain VALUES (NULL, x'', NULL, 0), (1, x'00ff10', 'two
            lines; -- not a comment /* nor this */', 4.9E-324), (2, NULL, 'Zoë 😀 日本', 1.7976931348623157E308);
            INSERT INTO auto (v) VALUES ('a'), ('b');
            INSERT INTO "if" VALUES (7);
            INSERT INTO plain VALUES (3, NULL, 'inf', 1e308 * 10), (4, NULL, '-inf', -1e308 * 10);
            """.trimIndent(),
        )
        assertEquals(0, setup.status, setup.err)
        val dump = run("-c", ".dump")
        assertEquals(0, dump.status, dump.err)
        assertTrue(dump.out.startsWith("BEGIN;\n") && dump.out.endsWith("COMMIT;\n"))

        val copy = dir.resolve("copy.db")
        val dumpFile = dir.resolve("dump.sql")
        Files.writeString(dumpFile, dump.out)
        val restored = cliOn(copy, listOf("-f", dumpFile.toString()), "", false)
        assertEquals(0, restored.status, restored.err)
        assertEquals(dump.out, cliOn(copy, listOf("-c", ".dump"), "", false).out)

        for (query in listOf("SELECT * FROM \"Order\"", "SELECT * FROM plain", "SELECT * FROM auto", "SELECT * FROM \"if\"")) {
            assertEquals(run("--mode", "json", "-c", query).out, cliOn(copy, listOf("--mode", "json", "-c", query), "", false).out, query)
        }
    }

    @Test
    fun `dump and restore of random data`() {
        val random = Random(42)
        val alphabet = "ab 'x\"\n;-/*\\é😀日,".codePoints().toArray()
        fun textValue(): String {
            val out = StringBuilder()
            repeat(random.nextInt(0, 12)) { out.appendCodePoint(alphabet[random.nextInt(alphabet.size)]) }
            return out.toString()
        }
        Database.open(file).use { db ->
            db.execute("CREATE TABLE r (k INTEGER PRIMARY KEY, i INTEGER, d REAL, s TEXT, b BLOB)")
            db.execute("CREATE TABLE loose (i INTEGER, s TEXT)")
            db.transaction {
                repeat(300) { n ->
                    val i: Long? = when (random.nextInt(5)) { 0 -> null; 1 -> Long.MIN_VALUE; 2 -> Long.MAX_VALUE; else -> random.nextLong() }
                    val d: Double? = when (random.nextInt(5)) {
                        0 -> null
                        1 -> Double.MAX_VALUE
                        2 -> Double.MIN_VALUE
                        else -> (random.nextDouble() - 0.5) * Math.pow(10.0, random.nextInt(-300, 300).toDouble())
                    }
                    val s: String? = if (random.nextInt(6) == 0) null else textValue()
                    val b: ByteArray? = if (random.nextInt(6) == 0) null else random.nextBytes(random.nextInt(0, 6))
                    db.execute("INSERT INTO r VALUES (?, ?, ?, ?, ?)", n, i, d, s, b)
                    db.execute("INSERT INTO loose VALUES (?, ?)", i, s)
                }
            }
        }
        val dumpFile = dir.resolve("random.sql")
        Files.writeString(dumpFile, run("-c", ".dump").out)
        val copy = dir.resolve("random-copy.db")
        assertEquals(0, cliOn(copy, listOf("-f", dumpFile.toString()), "", false).status)

        fun rows(path: Path, sql: String) = Database.open(path).use { db ->
            db.execute(sql).rows.map { row -> row.values.map { if (it is ByteArray) it.toList() else it } }
        }
        for (sql in listOf("SELECT * FROM r", "SELECT * FROM loose")) {
            assertEquals(rows(file, sql), rows(copy, sql), sql)
        }
    }
}
