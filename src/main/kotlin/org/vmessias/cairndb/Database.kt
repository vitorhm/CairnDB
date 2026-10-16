package org.vmessias.cairndb

import org.vmessias.cairndb.btree.BTree
import org.vmessias.cairndb.sql.SqlEngine
import org.vmessias.cairndb.sql.SqlResult
import org.vmessias.cairndb.sql.TableInfo
import org.vmessias.cairndb.storage.Pager
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * An embedded, single-file key/value database.
 *
 * Data is organised in named [Bucket]s, each one a B+Tree of byte-array keys and values. A catalog
 * tree, rooted at a page recorded in the file header, maps bucket names to their root pages.
 *
 * Concurrency: any number of readers or a single writer at a time within one process; the file is
 * locked so a second process (or a second [open] in this JVM) fails with [DatabaseLockedException].
 *
 * Durability: every write operation, or every [transaction], is atomic and durable (fsync'ed, see
 * [DatabaseOptions.syncOnCommit]) when it returns. Commits are protected by a rollback journal
 * (`<file>-journal`): a crash or I/O error in the middle of one is undone, automatically on the next
 * [open] or immediately. Pages touched by a transaction are held in memory until it commits.
 */
public class Database private constructor(internal val pager: Pager) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private val roots = ConcurrentHashMap<String, Int>()

    @Volatile
    private var closed = false

    // Both are only touched by the thread that holds the write lock.
    private var aborted = false
    private var activeScans = 0

    /** Longest key, in bytes, that buckets of this database accept. */
    public val maxKeySize: Int = BTree.maxKeySize(pager.pageSize)

    /**
     * Runs [block] as one atomic, isolated and durable unit: everything it writes, in any bucket,
     * becomes visible and durable together when it returns, or not at all if it throws.
     *
     * Other threads block, readers included, until the transaction ends; inside the block the calling
     * thread sees its own uncommitted changes through the usual [Bucket] methods. Nested calls to
     * [transaction], and plain bucket operations, join the running transaction. Since there are no
     * savepoints, an operation that fails inside a transaction (other than for invalid arguments)
     * dooms it: later operations throw, and so does the end of the outermost block, even if the
     * exception was caught.
     */
    public fun <T> transaction(block: () -> T): T = write(block)

    /**
     * Executes one SQL statement and returns its result; see the README for the supported dialect.
     * `?` placeholders are bound, in order, to [params] (null, integers, floating point numbers,
     * strings, byte arrays and booleans, the last stored as 1 or 0).
     *
     * A query runs under the read lock; any other statement is atomic and, outside [transaction],
     * committed before this returns. Inside [transaction] statements join it, and a statement that
     * fails dooms it like any failing operation. Tables live in buckets whose names start with `sql.`;
     * do not touch those directly.
     *
     * @throws org.vmessias.cairndb.sql.SqlException if the statement is invalid or cannot be applied.
     */
    public fun execute(sql: String, vararg params: Any?): SqlResult = SqlEngine(this).execute(sql, params)

    /** The SQL tables, ordered by lower-cased name. */
    public fun tables(): List<TableInfo> = SqlEngine(this).tables()

    /** The SQL table called [name] (case-insensitive), or null if there is none. */
    public fun table(name: String): TableInfo? = SqlEngine(this).tableInfo(name)

    /** Returns the bucket called [name], creating it if it does not exist yet. */
    public fun bucket(name: String): Bucket {
        val encoded = encodeName(name)
        if (read { rootOf(name, encoded) } == null) {
            write { if (rootOf(name, encoded) == null) createBucket(name, encoded) }
        }
        return Bucket(this, name)
    }

    /** Returns the bucket called [name] if it exists; unlike [bucket] it never creates one. */
    internal fun bucketOrNull(name: String): Bucket? =
        if (read { rootOf(name, encodeName(name)) } != null) Bucket(this, name) else null

    /** Names of all buckets, in byte order. */
    public fun bucketNames(): List<String> = read {
        catalog().scan(null, null).asSequence().map { it.key.decodeToString() }.toList()
    }

    /** Deletes the bucket and everything in it; returns false if it did not exist. */
    public fun dropBucket(name: String): Boolean {
        val encoded = encodeName(name)
        return write {
            val root = rootOf(name, encoded) ?: return@write false
            BTree(pager, root).destroy()
            catalog().delete(encoded)
            roots.remove(name)
            true
        }
    }

    /**
     * Reads the whole file: verifies every page checksum, then walks every tree and the free list
     * checking ordering, structure and page accounting. Throws [CorruptDatabaseException] describing
     * the first problem found.
     */
    public fun verify() {
        read {
            val seen = BitSet(pager.pageCount)
            pager.verifyChecksums()
            val visit = { id: Int ->
                if (seen[id]) throw CorruptDatabaseException("Page $id is used more than once")
                seen.set(id)
            }
            val catalog = catalog()
            catalog.verify(visit)
            for (entry in catalog.scan(null, null)) {
                BTree(pager, decodeRoot(entry.value)).verify(visit)
            }
            pager.freeListIds().forEach(visit)
            val expected = pager.pageCount - 1
            if (seen.cardinality() != expected) {
                throw CorruptDatabaseException("$expected pages in file but ${seen.cardinality()} are reachable: leaked pages")
            }
        }
    }

    override fun close() {
        check(!lock.isWriteLockedByCurrentThread) { "Cannot close the database inside a transaction" }
        lock.writeLock().lock()
        try {
            if (closed) return
            closed = true
            pager.close()
        } finally {
            lock.writeLock().unlock()
        }
    }

    internal fun checkKey(key: ByteArray) {
        require(key.size <= maxKeySize) { "Key is ${key.size} bytes, the maximum is $maxKeySize" }
    }

    internal fun treeOf(name: String): BTree {
        val root = rootOf(name, name.toByteArray()) ?: throw DatabaseException("Bucket '$name' does not exist")
        return BTree(pager, root)
    }

    /** Runs [block] under the read lock; [scan] marks a cursor that must not be invalidated by writes. */
    internal fun <T> read(scan: Boolean = false, block: () -> T): T {
        lock.readLock().lock()
        try {
            ensureUsable()
            val insideTransaction = scan && lock.isWriteLockedByCurrentThread
            if (insideTransaction) activeScans++
            try {
                val result = block()
                pager.trim()
                return result
            } finally {
                if (insideTransaction) activeScans--
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    internal fun <T> write(block: () -> T): T {
        if (lock.isWriteLockedByCurrentThread) return joinTransaction(block)
        check(lock.readHoldCount == 0) { "Cannot write while a scan or read is in progress on the same thread" }
        lock.writeLock().lock()
        try {
            ensureUsable()
            aborted = false
            try {
                val result = block()
                if (aborted) throw DatabaseException("The transaction was aborted by an earlier error and cannot commit")
                pager.commit()
                return result
            } catch (t: Throwable) {
                pager.rollback()
                roots.clear()
                throw t
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun <T> joinTransaction(block: () -> T): T {
        ensureUsable()
        check(!aborted) { "The transaction was aborted by an earlier error" }
        check(activeScans == 0) { "Cannot write while a scan is in progress" }
        try {
            return block()
        } catch (t: Throwable) {
            aborted = true
            throw t
        }
    }

    private fun ensureUsable() {
        check(!closed) { "Database is closed" }
        if (pager.failed) throw DatabaseException("An I/O error left the file in an unknown state; close and reopen the database")
    }

    private fun catalog(): BTree = BTree(pager, pager.catalogRoot)

    /** Resolves a bucket's root page through the cache or the catalog; callers hold the lock. */
    private fun rootOf(name: String, encoded: ByteArray): Int? {
        roots[name]?.let { return it }
        val value = catalog().get(encoded) ?: return null
        return decodeRoot(value).also { roots[name] = it }
    }

    private fun createBucket(name: String, encoded: ByteArray) {
        val tree = BTree.create(pager)
        catalog().put(encoded, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(tree.rootId).array())
        roots[name] = tree.rootId
    }

    private fun decodeRoot(value: ByteArray): Int {
        if (value.size != Int.SIZE_BYTES) throw CorruptDatabaseException("Malformed catalog entry")
        return ByteBuffer.wrap(value).getInt()
    }

    private fun encodeName(name: String): ByteArray {
        require(name.isNotEmpty()) { "Bucket name must not be empty" }
        return name.toByteArray().also { checkKey(it) }
    }

    public companion object {
        /** Opens the database at [path], creating the file if it does not exist. */
        @JvmStatic
        @JvmOverloads
        public fun open(path: Path, options: DatabaseOptions = DatabaseOptions()): Database {
            val pager = Pager.open(path, options)
            try {
                if (pager.catalogRoot == 0) {
                    pager.catalogRoot = BTree.create(pager).rootId
                    pager.commit()
                }
                return Database(pager)
            } catch (t: Throwable) {
                pager.close()
                throw t
            }
        }
    }
}
