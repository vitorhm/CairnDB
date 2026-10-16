package org.vmessias.cairndb.storage

import org.vmessias.cairndb.CorruptDatabaseException
import org.vmessias.cairndb.DatabaseException
import org.vmessias.cairndb.DatabaseLockedException
import org.vmessias.cairndb.DatabaseOptions
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Points of [Pager.commit] where tests can observe or interrupt the on-disk state. */
internal enum class CommitStep {
    /** Journal records written, journal header not yet: the journal is not hot. */
    JOURNAL_RECORDS_WRITTEN,

    /** The journal is hot, the database file has not been touched. */
    JOURNAL_HEADER_WRITTEN,

    /** One page (the id is reported) was written to the database file; page 0 is written last. */
    PAGE_WRITTEN,

    /** All pages are written and fsynced; the journal is still hot. */
    DATABASE_SYNCED,

    /** The journal is empty: the commit is final. Reported after the pager's state is updated. */
    JOURNAL_INVALIDATED,
}

/**
 * Fixed-size page I/O on top of a single file, with a page cache, a free list, page checksums,
 * crash-safe commits and all-or-nothing in-memory transactions.
 *
 * Dirty pages are never evicted: they stay in memory until [commit] or [rollback]. Clean pages are
 * only evicted by [trim], which callers invoke between operations, so a page reference obtained
 * during an operation never goes stale. This also means the size of a transaction is bounded by
 * memory.
 *
 * [commit] saves the original content of every page it is about to overwrite in a [Journal] and only
 * then touches the database file, so a crash (or an I/O error) at any point leaves either the old or
 * the new state: a hot journal found by [open] is rolled back, and a failed commit is rolled back
 * immediately.
 */
internal class Pager private constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val journal: Journal,
    private var header: FileHeader,
    private var persistedPageCount: Int,
    private val cacheCapacity: Int,
    private val syncOnCommit: Boolean,
) : Closeable {
    val pageSize: Int = header.pageSize

    private val cache = LinkedHashMap<Int, Page>(256, 0.75f, true)
    private val dirtyPages = ArrayList<Page>()
    private var committedHeader: FileHeader = header.copy()
    private var headerDirty = false

    /** Test hook, see [CommitStep]; the second argument is a page id for [CommitStep.PAGE_WRITTEN], else -1. */
    var commitHook: ((CommitStep, Int) -> Unit)? = null

    /** Set when a failed commit could not be undone; the file content is unknown from then on. */
    @Volatile
    var failed: Boolean = false
        private set

    val pageCount: Int @Synchronized get() = header.pageCount

    val freePageCount: Int @Synchronized get() = header.freePageCount

    var catalogRoot: Int
        @Synchronized get() = header.catalogRoot
        @Synchronized set(value) {
            header.catalogRoot = value
            headerDirty = true
        }

    @Synchronized
    fun read(id: Int): Page {
        if (id <= 0 || id >= header.pageCount) throw CorruptDatabaseException("Page $id is out of range")
        cache[id]?.let { return it }
        val page = Page(id, ByteArray(pageSize))
        channel.readFully(page.data, id.toLong() * pageSize)
        if (!page.hasValidChecksum()) throw CorruptDatabaseException("Page $id: checksum mismatch")
        cache[id] = page
        return page
    }

    /**
     * Re-reads every page that is on disk and not modified in memory, bypassing the cache, and checks
     * its checksum. Throws [CorruptDatabaseException] for the first page that does not match.
     */
    @Synchronized
    fun verifyChecksums() {
        val data = ByteArray(pageSize)
        for (id in 1 until persistedPageCount) {
            if (cache[id]?.dirty == true) continue
            channel.readFully(data, id.toLong() * pageSize)
            if (!Page.matchesChecksum(id, data)) throw CorruptDatabaseException("Page $id: checksum mismatch")
        }
    }

    /** Returns a zero-filled, dirty page, reusing a free page when there is one. */
    @Synchronized
    fun allocate(): Page {
        val page: Page
        if (header.freeListHead != 0) {
            page = read(header.freeListHead)
            header.freeListHead = page.buf.getInt(FREE_NEXT_OFFSET)
            header.freePageCount--
            page.data.fill(0)
        } else {
            if (header.pageCount == Int.MAX_VALUE) throw DatabaseException("Database file is full")
            page = Page(header.pageCount++, ByteArray(pageSize))
            cache[page.id] = page
        }
        headerDirty = true
        markDirty(page)
        return page
    }

    @Synchronized
    fun free(id: Int) {
        val page = read(id)
        page.data.fill(0)
        page.buf.putInt(FREE_NEXT_OFFSET, header.freeListHead)
        header.freeListHead = id
        header.freePageCount++
        headerDirty = true
        markDirty(page)
    }

    @Synchronized
    fun markDirty(page: Page) {
        if (!page.dirty) {
            page.dirty = true
            dirtyPages.add(page)
        }
    }

    /** Ids of every page currently on the free list, following the chain from the header. */
    @Synchronized
    fun freeListIds(): List<Int> {
        val ids = ArrayList<Int>()
        var next = header.freeListHead
        while (next != 0) {
            if (ids.size >= header.freePageCount) throw CorruptDatabaseException("Free list is longer than recorded")
            ids += next
            next = read(next).buf.getInt(FREE_NEXT_OFFSET)
        }
        if (ids.size != header.freePageCount) throw CorruptDatabaseException("Free list is shorter than recorded")
        return ids
    }

    /**
     * Makes every change since the last commit durable, atomically. If anything fails the database
     * file is restored from the journal, the in-memory changes are discarded and the cause rethrown.
     */
    @Synchronized
    fun commit() {
        if (dirtyPages.isEmpty() && !headerDirty) return
        val pages = dirtyPages.sortedBy { it.id }
        // A brand-new file has nothing to protect: there is no previous state to go back to.
        val journaled = persistedPageCount > 0
        try {
            if (journaled) {
                val saved = ArrayList<Int>(pages.size + 1)
                saved += 0
                for (page in pages) if (page.id < persistedPageCount) saved += page.id
                journal.begin(
                    db = channel,
                    pageSize = pageSize,
                    originalPageCount = persistedPageCount,
                    pageIds = saved,
                    afterRecords = { commitHook?.invoke(CommitStep.JOURNAL_RECORDS_WRITTEN, -1) },
                    afterHeader = { commitHook?.invoke(CommitStep.JOURNAL_HEADER_WRITTEN, -1) },
                )
            }
            header.changeCounter++
            for (page in pages) {
                page.stampChecksum()
                channel.writeFully(page.data, page.id.toLong() * pageSize)
                commitHook?.invoke(CommitStep.PAGE_WRITTEN, page.id)
            }
            val headerPage = ByteArray(pageSize)
            header.encodeInto(headerPage)
            channel.writeFully(headerPage, 0)
            commitHook?.invoke(CommitStep.PAGE_WRITTEN, 0)
            if (syncOnCommit) channel.force(true)
            commitHook?.invoke(CommitStep.DATABASE_SYNCED, -1)
            if (journaled) journal.commit()
        } catch (t: Throwable) {
            if (journaled) {
                try {
                    journal.rollback(channel)
                } catch (restoreFailure: Throwable) {
                    failed = true
                    t.addSuppressed(restoreFailure)
                }
            } else {
                failed = true
            }
            rollback()
            throw t
        }
        persistedPageCount = header.pageCount
        dirtyPages.forEach { it.dirty = false }
        dirtyPages.clear()
        headerDirty = false
        committedHeader = header.copy()
        trim()
        commitHook?.invoke(CommitStep.JOURNAL_INVALIDATED, -1)
    }

    /** Discards every change made since the last [commit]. */
    @Synchronized
    fun rollback() {
        dirtyPages.clear()
        cache.clear()
        header = committedHeader.copy()
        headerDirty = false
    }

    /** Evicts the least recently used clean pages while the cache is over capacity. */
    @Synchronized
    fun trim() {
        if (cache.size <= cacheCapacity) return
        val it = cache.entries.iterator()
        while (cache.size > cacheCapacity && it.hasNext()) {
            if (!it.next().value.dirty) it.remove()
        }
    }

    @Synchronized
    override fun close() {
        if (!channel.isOpen) return
        try {
            journal.close()
            if (fileLock.isValid) fileLock.release()
        } finally {
            channel.close()
        }
        cache.clear()
        dirtyPages.clear()
    }

    companion object {
        /** Offset of the next-free-page pointer inside a free page. Byte 0 stays 0 (the "free" type). */
        private const val FREE_NEXT_OFFSET = 4

        /**
         * The file is locked through a single byte far beyond any real data. Locking the data itself
         * would, on Windows, stop every other handle (backups, tests, tools) from even reading it.
         */
        private const val LOCK_POSITION = 1L shl 40

        fun journalPath(path: Path): Path = path.resolveSibling(path.fileName.toString() + "-journal")

        fun open(path: Path, options: DatabaseOptions): Pager {
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            try {
                val lock = lock(channel, path)
                try {
                    return openLocked(channel, lock, path, options)
                } catch (t: Throwable) {
                    lock.release()
                    throw t
                }
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }

        private fun lock(channel: FileChannel, path: Path): FileLock {
            val lock = try {
                channel.tryLock(LOCK_POSITION, 1, false)
            } catch (e: OverlappingFileLockException) {
                throw DatabaseLockedException("Database is already open in this process: $path", e)
            } catch (e: IOException) {
                throw DatabaseLockedException("Could not lock database file: $path", e)
            }
            return lock ?: throw DatabaseLockedException("Database is open in another process: $path")
        }

        private fun openLocked(channel: FileChannel, lock: FileLock, path: Path, options: DatabaseOptions): Pager {
            val journalFile = journalPath(path)
            val journal = Journal(journalFile, options.syncOnCommit)
            try {
                // Holding the file lock, any journal still hot belongs to a commit that never finished.
                if (Files.exists(journalFile)) journal.rollback(channel)

                val size = channel.size()
                if (size == 0L) {
                    val initial = FileHeader.initial(options.pageSize)
                    val pager = Pager(channel, lock, journal, initial, 0, options.cachePages, options.syncOnCommit)
                    pager.headerDirty = true
                    pager.commit()
                    return pager
                }
                if (size < FileHeader.SIZE) throw CorruptDatabaseException("File is too small to be a database")
                val raw = ByteArray(FileHeader.SIZE)
                channel.readFully(raw, 0)
                val header = FileHeader.decode(raw)
                if (size < header.pageCount.toLong() * header.pageSize) {
                    throw CorruptDatabaseException("File is truncated: expected ${header.pageCount} pages of ${header.pageSize} bytes")
                }
                return Pager(channel, lock, journal, header, header.pageCount, options.cachePages, options.syncOnCommit)
            } catch (t: Throwable) {
                journal.close()
                throw t
            }
        }
    }
}
