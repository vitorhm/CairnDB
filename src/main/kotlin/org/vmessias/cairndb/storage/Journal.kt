package org.vmessias.cairndb.storage

import org.vmessias.cairndb.CorruptDatabaseException
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Rollback journal: before a commit overwrites pages of the database file, their current content is
 * saved here so that an interrupted commit can be undone, whether the process dies or a write fails.
 *
 * ```
 * header (32 bytes) : magic "CAIRNJ01" | page size u32 | original page count u32 | record count u32 | CRC-32 u32
 * record            : page id u32 | page content | CRC-32 of (id + content) u32
 * ```
 *
 * Protocol, with [begin] / [commit]:
 * 1. write the records, fsync;
 * 2. write the header, fsync. From here on the journal is *hot*: the database file may be modified;
 * 3. the caller overwrites the database pages and fsyncs the database file;
 * 4. [commit] truncates the journal to zero bytes and fsyncs it. This is the commit point.
 *
 * A journal whose header is missing or fails its checksum never reached step 2, so the database file
 * is untouched and the journal is discarded. A hot journal is applied by [rollback], which restores
 * the saved pages and the original file size.
 *
 * The file stays open between commits and is deleted on [close] when empty.
 */
internal class Journal(private val path: Path, private val sync: Boolean) : Closeable {
    private var channel: FileChannel? = null

    private fun channel(): FileChannel {
        channel?.let { return it }
        val existed = Files.exists(path)
        val opened = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        channel = opened
        if (!existed && sync) syncDirectory()
        return opened
    }

    /**
     * Saves the current content of [pageIds] (read from [db]) plus the database size, leaving a hot
     * journal on disk. [afterRecords] runs once the records are written but the header is not,
     * [afterHeader] once the journal is hot; both exist so tests can interrupt the protocol.
     */
    fun begin(
        db: FileChannel,
        pageSize: Int,
        originalPageCount: Int,
        pageIds: List<Int>,
        afterRecords: () -> Unit,
        afterHeader: () -> Unit,
    ) {
        val journal = channel()
        if (journal.size() != 0L) journal.truncate(0)
        val record = ByteArray(recordSize(pageSize))
        val recordBuffer = ByteBuffer.wrap(record)
        var position = HEADER_SIZE.toLong()
        for (id in pageIds) {
            db.readFully(ByteBuffer.wrap(record, RECORD_ID_SIZE, pageSize), id.toLong() * pageSize)
            recordBuffer.putInt(0, id)
            recordBuffer.putInt(RECORD_ID_SIZE + pageSize, crc(record, 0, RECORD_ID_SIZE + pageSize))
            journal.writeFully(record, position)
            position += record.size
        }
        afterRecords()
        if (sync) journal.force(true)

        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header)
        val buf = ByteBuffer.wrap(header)
        buf.putInt(8, pageSize)
        buf.putInt(12, originalPageCount)
        buf.putInt(16, pageIds.size)
        buf.putInt(20, crc(header, 0, 20))
        journal.writeFully(header, 0)
        if (sync) journal.force(true)
        afterHeader()
    }

    /** Invalidates the journal: the commit it protected is now final. */
    fun commit() {
        val journal = channel()
        journal.truncate(0)
        if (sync) journal.force(true)
    }

    /**
     * Undoes the commit protected by a hot journal, if there is one: restores the saved pages and the
     * original size of [db], then empties the journal. Returns whether anything was restored.
     * Always fsyncs, as it runs when something already went wrong.
     *
     * @throws CorruptDatabaseException if the journal is hot but damaged; nothing is modified then.
     */
    fun rollback(db: FileChannel): Boolean {
        val journal = channel()
        val size = journal.size()
        if (size == 0L) return false
        val header = readHeader(journal, size)
        if (header == null) {
            journal.truncate(0)
            journal.force(true)
            return false
        }
        val recordSize = recordSize(header.pageSize)
        if (size < HEADER_SIZE + header.records.toLong() * recordSize) {
            throw CorruptDatabaseException("Hot journal is truncated")
        }

        val record = ByteArray(recordSize)
        val recordBuffer = ByteBuffer.wrap(record)
        for (pass in 0..1) {
            var position = HEADER_SIZE.toLong()
            repeat(header.records) {
                journal.readFully(record, position)
                position += recordSize
                val id = recordBuffer.getInt(0)
                if (recordBuffer.getInt(RECORD_ID_SIZE + header.pageSize) != crc(record, 0, RECORD_ID_SIZE + header.pageSize) ||
                    id < 0 || id >= header.originalPageCount
                ) {
                    throw CorruptDatabaseException("Hot journal has a damaged record")
                }
                if (pass == 1) db.writeFully(ByteBuffer.wrap(record, RECORD_ID_SIZE, header.pageSize), id.toLong() * header.pageSize)
            }
        }
        db.truncate(header.originalPageCount.toLong() * header.pageSize)
        db.force(true)
        journal.truncate(0)
        journal.force(true)
        return true
    }

    override fun close() {
        val journal = channel ?: return
        channel = null
        try {
            val empty = journal.size() == 0L
            journal.close()
            if (empty) Files.deleteIfExists(path)
        } catch (ignored: IOException) {
            // Best effort: a leftover empty journal is harmless and is ignored on the next open.
        }
    }

    private class Header(val pageSize: Int, val originalPageCount: Int, val records: Int)

    private fun readHeader(journal: FileChannel, size: Long): Header? {
        if (size < HEADER_SIZE) return null
        val bytes = ByteArray(HEADER_SIZE)
        journal.readFully(bytes, 0)
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.getInt(20) != crc(bytes, 0, 20)) return null
        val pageSize = buf.getInt(8)
        val records = buf.getInt(16)
        if (pageSize < 512 || Integer.bitCount(pageSize) != 1 || records < 0) return null
        return Header(pageSize, buf.getInt(12), records)
    }

    /** Makes the creation of the journal file itself durable where the platform allows it. */
    private fun syncDirectory() {
        val directory = path.toAbsolutePath().parent ?: return
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (ignored: IOException) {
            // Not supported on every platform (e.g. Windows refuses to open directories).
        }
    }

    private companion object {
        const val HEADER_SIZE = 32
        const val RECORD_ID_SIZE = 4
        val MAGIC: ByteArray = "CAIRNJ01".toByteArray(Charsets.US_ASCII)

        fun recordSize(pageSize: Int) = RECORD_ID_SIZE + pageSize + 4

        fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
            val crc = CRC32()
            crc.update(bytes, offset, length)
            return crc.value.toInt()
        }
    }
}
