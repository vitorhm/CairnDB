package org.vmessias.cairndb.storage

import org.vmessias.cairndb.CorruptDatabaseException
import org.vmessias.cairndb.DatabaseOptions
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Contents of page 0.
 *
 * ```
 * offset size field
 *      0   16 magic
 *     16    4 page size
 *     20    4 format version
 *     24    4 page count (including page 0)
 *     28    4 free list head (0 = empty)
 *     32    4 free page count
 *     36    4 catalog root page
 *     40    8 change counter
 *     48    4 CRC-32 of bytes 0..47
 * ```
 */
internal data class FileHeader(
    val pageSize: Int,
    var pageCount: Int,
    var freeListHead: Int,
    var freePageCount: Int,
    var catalogRoot: Int,
    var changeCounter: Long,
) {
    fun encodeInto(target: ByteArray) {
        target.fill(0, 0, SIZE)
        MAGIC.copyInto(target)
        val buf = ByteBuffer.wrap(target)
        buf.putInt(16, pageSize)
        buf.putInt(20, FORMAT_VERSION)
        buf.putInt(24, pageCount)
        buf.putInt(28, freeListHead)
        buf.putInt(32, freePageCount)
        buf.putInt(36, catalogRoot)
        buf.putLong(40, changeCounter)
        buf.putInt(48, checksum(target))
    }

    companion object {
        const val SIZE = 52
        const val FORMAT_VERSION = 2
        val MAGIC: ByteArray = "CairnDB\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)

        fun initial(pageSize: Int) = FileHeader(
            pageSize = pageSize,
            pageCount = 1,
            freeListHead = 0,
            freePageCount = 0,
            catalogRoot = 0,
            changeCounter = 0,
        )

        fun decode(source: ByteArray): FileHeader {
            if (source.size < SIZE || !source.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw CorruptDatabaseException("Not a database file: bad magic")
            }
            val buf = ByteBuffer.wrap(source)
            if (buf.getInt(48) != checksum(source)) {
                throw CorruptDatabaseException("File header checksum mismatch")
            }
            val version = buf.getInt(20)
            if (version != FORMAT_VERSION) {
                throw CorruptDatabaseException("Unsupported format version $version")
            }
            val pageSize = buf.getInt(16)
            if (pageSize !in DatabaseOptions.MIN_PAGE_SIZE..DatabaseOptions.MAX_PAGE_SIZE ||
                Integer.bitCount(pageSize) != 1
            ) {
                throw CorruptDatabaseException("Invalid page size $pageSize")
            }
            val pageCount = buf.getInt(24)
            if (pageCount < 1) throw CorruptDatabaseException("Invalid page count $pageCount")
            return FileHeader(
                pageSize = pageSize,
                pageCount = pageCount,
                freeListHead = buf.getInt(28),
                freePageCount = buf.getInt(32),
                catalogRoot = buf.getInt(36),
                changeCounter = buf.getLong(40),
            )
        }

        private fun checksum(bytes: ByteArray): Int {
            val crc = CRC32()
            crc.update(bytes, 0, 48)
            return crc.value.toInt()
        }
    }
}
