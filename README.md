# CairnDB

**A small, embedded, single-file SQL database written in Kotlin, in the spirit of SQLite.**

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-orange.svg)
![Status](https://img.shields.io/badge/status-alpha-yellow.svg)

A *cairn* is a stack of stones that marks a trail. CairnDB is a stack of pages that marks where your
data lives: one file, no server, no runtime dependency besides the Kotlin standard library.

* **One file.** The whole database is a single file, plus a short-lived `-journal` file while a
  commit is in flight.
* **Crash safe.** A SQLite-style rollback journal makes every commit all-or-nothing. Tests simulate a
  crash at every step of a commit and check that reopening yields exactly the old or the new state.
* **Corruption is detected.** Every page carries a CRC-32; a flipped bit or a torn write is reported as
  a `CorruptDatabaseException` instead of returning bad data.
* **Three ways in.** An ordered key/value API, a SQL layer, and an interactive command-line shell.
* **Readable.** A B+Tree on slotted pages, a pager, a journal, a hand-written SQL lexer, parser,
  planner and executor, all in plain Kotlin that is meant to be read. See [How it works](#how-it-works).

> **Status: alpha.** CairnDB is young. The file format and the API may still change, and it has not
> been tested at scale. Use it to learn, to experiment, or for small tools; do not trust it with data
> you cannot lose yet. See [Known limitations](#known-limitations).

## Contents

* [Quick start](#quick-start)
* [Getting CairnDB](#getting-cairndb)
* [Command-line shell](#command-line-shell)
* [Using it as a library](#using-it-as-a-library): [key/value](#keyvalue-api), [transactions](#transactions), [SQL](#sql)
* [SQL reference](#sql-reference)
* [How it works](#how-it-works)
* [Known limitations](#known-limitations) and [Roadmap](#roadmap)
* [Development](#development) and [Contributing](#contributing)
* [License](#license)

## Quick start

Build the shell and open a database (the file is created if it does not exist):

```
./gradlew installDist
build/install/cairndb/bin/cairndb app.db
```

```
cairndb 0.1.0-SNAPSHOT
Connected to app.db. Type .help for help, .quit to leave.
sql> CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
OK
sql> INSERT INTO people (name, age) VALUES ('Ana', 31), ('Bruno', 17), ('Carla', 45);
3 rows affected (last id 3)
sql> SELECT name, age FROM people WHERE age >= 18 ORDER BY age DESC;
+-------+-----+
| name  | age |
+-------+-----+
| Carla |  45 |
| Ana   |  31 |
+-------+-----+
(2 rows)
sql> EXPLAIN SELECT * FROM people WHERE id BETWEEN 1 AND 2;
+---------------------------------------------------+
| plan                                              |
+---------------------------------------------------+
| SEARCH people USING PRIMARY KEY (id>=? AND id<=?) |
+---------------------------------------------------+
(1 row)
sql> BEGIN;
OK
sql*> DELETE FROM people WHERE age < 18;
1 row affected
sql*> ROLLBACK;
OK
sql> .quit
```

The same database from Kotlin:

```kotlin
import org.vmessias.cairndb.Database
import java.nio.file.Path

Database.open(Path.of("app.db")).use { db ->
    val adults = db.execute("SELECT name FROM people WHERE age >= ? ORDER BY name", 18)
    for (row in adults.rows) println(row["name"])
}
```

## Getting CairnDB

Requirements: **JDK 21**. Gradle downloads a matching JDK through its toolchain support when it builds
the project, but the start script of the shell uses whatever `java` is on your `PATH` (or `JAVA_HOME`),
so that must be 21 or newer.

CairnDB is not on Maven Central yet. To use it from another Gradle project, clone this repository next
to yours and include it as a composite build; Gradle substitutes the dependency by its coordinates:

```kotlin
// settings.gradle.kts of your project
includeBuild("../cairndb")

// build.gradle.kts of your project
dependencies {
    implementation("org.vmessias:cairndb:0.1.0-SNAPSHOT")
}
```

Or build the jar with `./gradlew jar` (`build/libs/cairndb-0.1.0-SNAPSHOT.jar`) and put it on the
classpath together with `kotlin-stdlib`, the only runtime dependency.

## Command-line shell

```
./gradlew installDist
build/install/cairndb/bin/cairndb app.db                          # interactive shell
build/install/cairndb/bin/cairndb app.db -c "SELECT * FROM people"
build/install/cairndb/bin/cairndb app.db -f schema.sql --bail
echo "SELECT 1 + 1;" | build/install/cairndb/bin/cairndb app.db
./gradlew run --args="app.db -c 'SELECT 1'"                       # without installing
```

(On Windows use `build\install\cairndb\bin\cairndb.bat` and `gradlew.bat`.) Without `-c`, `-f` or SQL
arguments the shell reads standard input: a prompt (`sql> `, and `sql*> ` inside a transaction) at a
terminal, a plain script otherwise. Statements end with `;` and may span several lines; `--` and
`/* */` comments are skipped.

| Option | Meaning |
|--------|---------|
| `-c, --command SQL` | run SQL (repeatable); arguments after the file are SQL too |
| `-f, --file SCRIPT` | run a script of statements and dot commands (repeatable) |
| `-i, --interactive` | read standard input with prompts, also after `-c` / `-f` |
| `-m, --mode MODE` | `table` (default), `list`, `csv` or `json` |
| `--no-headers` | do not print column names |
| `--bail` | stop at the first error |
| `--page-size N` | page size of a new database (power of two, 512 to 32768) |
| `--no-sync` | skip `fsync` on commit (not safe against power loss) |
| `-h, --help`, `--version` | show help or the version |

Dot commands (alone on a line): `.help`, `.quit`/`.exit`, `.tables`, `.schema [TABLE]`, `.dump [TABLE]`,
`.read FILE`, `.mode [MODE]`, `.headers [on|off]`, `.timer [on|off]` and `.verify`.

`BEGIN`, `COMMIT` (or `END`) and `ROLLBACK` work in the shell, backed by `Database.transaction`. As in the
library, an error in a transaction dooms it: later writes are refused and `COMMIT` rolls it back and says
so, so end it with `ROLLBACK`. A transaction still open at the end of the input is rolled back with a
warning.

**Backup and restore.** `.dump` writes `BEGIN; CREATE TABLE ...; INSERT ...; COMMIT;`, which `.read` or
`-f` replays into an empty database:

```
cairndb app.db -c ".dump" > backup.sql
cairndb restored.db -f backup.sql
```

Every value survives the trip, including `BLOB`s, the smallest `INTEGER`, infinities, and reserved-word
or unusual identifiers (they are quoted).

**Errors and exit status.** Errors go to standard error as `Error: message` in the interactive shell and
as `source:line: Error: message` otherwise, with the offending line and a caret for syntax errors. The
exit status is 0 on success, 1 if the database cannot be opened or a statement failed (non-interactive
runs), and 2 for a bad command line.

## Using it as a library

### Key/value API

Buckets are ordered maps from `ByteArray` keys to `ByteArray` values (with `String` conveniences):

```kotlin
import org.vmessias.cairndb.Database
import java.nio.file.Path

Database.open(Path.of("app.db")).use { db ->
    val users = db.bucket("users")          // created on first use, like a table

    users.put("alice", "admin")
    users.put("bob", "editor")
    users.get("alice")                      // "admin"
    users.delete("bob")                     // true

    // Ordered range scan: from <= key < to (null = open). Valid only inside the block.
    users.scan(from = "a".toByteArray(), to = "n".toByteArray()) { entries ->
        entries.forEach { println("${it.key.decodeToString()} -> ${it.value.decodeToString()}") }
    }

    db.bucketNames()                        // ["users"]
    db.verify()                             // full integrity check, throws CorruptDatabaseException
}
```

Keys sort as unsigned bytes. Keys may be up to `pageSize / 8` bytes (`db.maxKeySize`); values can be
arbitrarily large.

### Transactions

Every single operation is atomic and durable on its own. To group several operations (even across
buckets) into one all-or-nothing commit, use `transaction`:

```kotlin
val orders = db.bucket("orders")
val stock = db.bucket("stock")

db.transaction {
    orders.put("o-1", "2 x widget")
    stock.put("widget", "98")
    // An exception thrown here rolls back everything, including created or dropped buckets.
}
```

Inside the block the calling thread sees its own uncommitted changes; other threads wait. Nested
`transaction` calls join the outer one. There are no savepoints: if an operation inside a transaction
fails, the transaction is doomed even if the exception is caught, and the outermost block throws
`DatabaseException`.

### SQL

```kotlin
Database.open(Path.of("app.db")).use { db ->
    db.execute("""
        CREATE TABLE IF NOT EXISTS people (
            id    INTEGER PRIMARY KEY,
            name  TEXT NOT NULL,
            age   INTEGER,
            photo BLOB
        )
    """)

    val insert = db.execute("INSERT INTO people (name, age) VALUES (?, ?), (?, ?)", "Ana", 31, "Bruno", null)
    insert.rowsAffected                     // 2
    insert.lastInsertId                     // 2 (ids are assigned and never reused)

    val result = db.execute("SELECT name, age FROM people WHERE age >= ? ORDER BY name LIMIT 10", 18)
    for (row in result.rows) println("${row["name"]} ${row[1]}")   // TEXT -> String, INTEGER -> Long, ...

    // Statements are atomic on their own; group several into one all-or-nothing commit:
    db.transaction {
        db.execute("UPDATE people SET age = age + 1 WHERE id = ?", 1)
        db.execute("DELETE FROM people WHERE name LIKE 'B%'")
    }

    db.execute("EXPLAIN SELECT * FROM people WHERE id BETWEEN 1 AND 5").rows   // SEARCH people USING PRIMARY KEY (id>=? AND id<=?)
}
```

`Database.execute(sql, vararg params)` runs one statement, binding `?` placeholders in order, and returns
a `SqlResult` (`columns`, `rows`, `rowsAffected`, `lastInsertId`). Parameters and results use `null`
(NULL), `Long` (INTEGER), `Double` (REAL), `String` (TEXT) and `ByteArray` (BLOB); `Int`, `Short`, `Byte`,
`Float` and `Boolean` are accepted as parameters. Errors throw `SqlException` (with the position in the
statement when it is a syntax error).

`Database.tables()` and `Database.table(name)` describe the schema as `TableInfo` / `ColumnInfo` objects;
`TableInfo.createStatement()` renders a `CREATE TABLE` statement that recreates the table.

## SQL reference

**Statements**

* `CREATE TABLE [IF NOT EXISTS]` with `PRIMARY KEY` (column or table constraint, composite allowed) and
  `NOT NULL`; `DROP TABLE [IF EXISTS]`.
* `INSERT INTO t [(columns)] VALUES (...), (...)`.
* `SELECT [DISTINCT] ... [FROM t] [WHERE] [GROUP BY] [HAVING] [ORDER BY ... ASC|DESC] [LIMIT n [OFFSET m]]`,
  with aliases (`AS`) and `*`. `FROM` is optional (`SELECT 1 + 1`).
* `UPDATE t SET c = expr, ... [WHERE]`, `DELETE FROM t [WHERE]`.
* `EXPLAIN` in front of `SELECT`, `UPDATE` or `DELETE` shows the access path.

**Types**: `INTEGER` (also `INT`, `BIGINT`, `SMALLINT`, `TINYINT`), `REAL` (`FLOAT`, `DOUBLE [PRECISION]`),
`TEXT` (`VARCHAR`, `CHAR`, `STRING`), `BLOB`. `TRUE` and `FALSE` are the integers 1 and 0. Typing is
strict: a value must already have the column's type (an integer is accepted in a REAL column); there is no
implicit conversion between text and numbers. NaN is rejected. Blob literals are `x'CAFE'`.

**Expressions**: `+ - * / %`, `||`, comparisons, `AND`/`OR`/`NOT`, `IS [NOT] NULL`, `[NOT] LIKE` (`%`, `_`,
ASCII case-insensitive), `[NOT] IN (...)`, `[NOT] BETWEEN`; functions `LOWER`, `UPPER`, `LENGTH`, `ABS`,
`COALESCE`, `IFNULL`, `TYPEOF`; aggregates `COUNT(*)`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`. Logic is
three-valued (`NULL` propagates, `WHERE` keeps only true). Integer overflow and division by zero are
errors. Numbers of different types compare exactly; text compares by code point (the same as UTF-8 byte
order); `NULL` sorts first.

**Storage and planning**: a table is a bucket named `sql.table.<name>` and the schema lives in the bucket
`sql.schema` (the `sql.` prefix is reserved). Rows are stored under the order-preserving encoding of the
primary key, or of a hidden row id when there is none; a single `INTEGER PRIMARY KEY` is assigned
automatically from a persisted counter. The planner looks at the `AND`-ed conditions of a `WHERE` on the
leading primary-key columns (`=`, `<`, `<=`, `>`, `>=`, `BETWEEN`) and chooses between a point lookup, a
range scan and a full scan. The whole `WHERE` is always applied to the rows found, so the plan only
affects speed, never the result.

**Limits**: one table per statement (no joins, subqueries or secondary indexes); no SQL-level
`BEGIN`/`COMMIT` in `Database.execute` (use `db.transaction { }`; the shell does support them); results
are fully materialised in memory; an error inside a `transaction` dooms it even if caught; `TEXT` cannot
hold unpaired UTF-16 surrogates; primary keys are limited by the key size (`db.maxKeySize`, which is
`pageSize / 8`: 512 bytes with the default 4096-byte pages).

## How it works

| Layer | Code | Responsibility |
|-------|------|----------------|
| Public API | `Database`, `Bucket` | Buckets, locking, transactions |
| B+Tree | `btree/BTree`, `Node`, `Cursor` | Ordered map on slotted pages, overflow chains, range scans |
| Pager | `storage/Pager`, `FileHeader` | Fixed-size pages, cache, free list, commit/rollback, file lock |
| Journal | `storage/Journal` | Rollback journal: crash-safe commits and recovery |
| SQL | `sql/Lexer`, `Parser`, `Planner`, `SqlEngine` | Tables as buckets, row/key encoding, planner, executor |
| CLI | `cli/Cli`, `Shell`, `Session`, `Dumper` | Argument parsing, statement splitting, output formats, shell transactions, dump |

* **File format** (version 2): page 0 is a header (magic `CairnDB`, page size, format version, page
  count, free list head, catalog root, change counter, CRC-32); every other page is a B+Tree node, an
  overflow page or a free page. All integers are big-endian. Page size is a power of two between 512 and
  32768 (default 4096), fixed when the file is created. Files written by earlier snapshots are rejected.
* **Page checksums**: the last 4 bytes of every page except page 0 are a CRC-32 of the page id and the
  rest of the page. It is stamped on every write and checked every time a page is read from disk, so a
  flipped bit, a torn write or a page written to the wrong place surfaces as a
  `CorruptDatabaseException` instead of bad data. `Database.verify()` also re-reads the whole file and
  checks every checksum, including pages the structure checks would not look at.
* **Buckets**: a catalog B+Tree maps bucket names to root pages. A tree's root page id never changes, so
  the catalog is only written when buckets are created or dropped.
* **Leaves** hold the entries and are chained left to right; **large values** (more than about a quarter
  of a page) go to overflow page chains. Deletes merge under-filled siblings and return freed pages to a
  free list that is reused before the file grows.
* **Concurrency**: many readers or one writer per process (`ReentrantReadWriteLock`). The file is locked,
  so a second open (same JVM or another process) fails with `DatabaseLockedException`.
* **Commits and crash safety** use a SQLite-style rollback journal, the file `<db>-journal`:
  1. the original content of every page about to be overwritten is appended to the journal, which is then
     sealed with a CRC-protected header and `fsync`ed;
  2. the dirty pages and the file header are written to the database and `fsync`ed;
  3. the journal is truncated, which is the commit point.

  If the process dies at any moment, the next `open` finds the hot journal, verifies it and puts the old
  pages back (and the old file size). If a commit fails with an I/O error, the same undo happens at once
  and the exception is rethrown, leaving the database usable. A journal that was never sealed means the
  database file was not touched, so it is discarded. A damaged hot journal is never applied: `open` throws
  `CorruptDatabaseException` and leaves the files as they are. A test snapshots the files after every step
  of a commit, tears the page just written and checks that reopening yields exactly the old or the new
  state.
* **Memory bound**: pages touched by a transaction stay in memory until it commits, so the size of a
  transaction is limited by the heap. `DatabaseOptions(syncOnCommit = false)` skips the `fsync`s: it
  survives a process crash but not a power loss.

## Known limitations

* Transactions are flat (no savepoints) and bounded by memory.
* Single process; no reverse scans; deletes merge siblings but never redistribute entries.
* SQL is limited to one table per statement: no joins, subqueries or secondary indexes yet.
* Checksums detect corruption but cannot repair it, and page 0 only protects its first 48 bytes.
* The journal is not tied to its database file: do not copy a `-journal` file between databases.
* The shell has no line editing or history beyond what the terminal provides. `.dump` writes one
  `INSERT` per row, a restore holds its whole transaction in memory, and the never-reused row-id counter
  is not restored (it restarts at the largest id + 1).
* The file format is not stable yet: files from a future version may not open with this one.

## Roadmap

1. Secondary indexes and use of them in the planner.
2. Joins.
3. A release on Maven Central.

## Development

Requires JDK 21 (Gradle resolves it through the toolchain).

```
./gradlew build          # compile and run the whole test suite
./gradlew test           # tests only
./gradlew installDist    # the command-line shell, in build/install/cairndb
```

The test suite includes a randomized comparison against `java.util.TreeMap` on 512-byte pages (deep trees,
splits, merges, overflow values, reopenings) with a full `verify()` every thousand operations,
crash-recovery tests that simulate a crash at every step of a commit, and concurrency tests for
transactions. The SQL layer has parser, expression, planner (including planned-versus-full-scan
equivalence on random data) and codec tests, plus a randomized model test that runs SQL statements,
transactions, rollbacks and reopenings against a `TreeMap`. The CLI has unit tests (statement splitter,
formatters, argument parser, transaction session) and end-to-end tests that drive the whole shell through
in-memory streams, including randomized dump/restore round trips.

Source layout: `src/main/kotlin/org/vmessias/cairndb/` holds the public API, with `btree`, `storage`,
`sql` and `cli` as subpackages; tests mirror it under `src/test`. The library uses Kotlin's explicit API
mode, so everything public is declared on purpose.

## Contributing

Issues and pull requests are welcome. Before opening a pull request, run `./gradlew build` and make sure
it passes with no warnings. Changes to the storage engine should come with tests that exercise crash
recovery or the randomized model tests, since those are what keep the file format honest. By contributing
you agree that your work is licensed under the Apache License 2.0.

## License

[Apache License 2.0](LICENSE)
