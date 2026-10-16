package org.vmessias.cairndb.storage

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * An in-memory copy of one fixed-size page of the database file. Page 0 is the file header.
 *
 * Every other page ends with a [CHECKSUM_SIZE]-byte CRC-32 of the page id followed by the rest of the
 * page. The [Pager] stamps it when the page is written and checks it when the page is read, so the
 * layers above must stay within [usableSize] bytes. Including the id also catches a page that was
 * written to the wrong place.
 */
internal class Page(val id: Int, val data: ByteArray) {
    /** Big-endian view over [data]; only absolute accessors are used so it is safe to share between readers. */
    val buf: ByteBuffer = ByteBuffer.wrap(data)

    var dirty: Boolean = false

    /** Bytes of the page that callers may use; the checksum lives right after them. */
    val usableSize: Int get() = usableSize(data.size)

    fun stampChecksum() {
        buf.putInt(usableSize, checksum(id, data))
    }

    fun hasValidChecksum(): Boolean = matchesChecksum(id, data)

    companion object {
        const val CHECKSUM_SIZE = 4

        fun usableSize(pageSize: Int): Int = pageSize - CHECKSUM_SIZE

        fun matchesChecksum(id: Int, data: ByteArray): Boolean =
            ByteBuffer.wrap(data).getInt(usableSize(data.size)) == checksum(id, data)

        private fun checksum(id: Int, data: ByteArray): Int {
            val crc = CRC32()
            for (shift in intArrayOf(24, 16, 8, 0)) crc.update(id ushr shift)
            crc.update(data, 0, usableSize(data.size))
            return crc.value.toInt()
        }
    }
}
