package org.vmessias.cairndb

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChecksumTest : DatabaseTestBase() {
    private val pageSize = 512

    /** A file with leaves, interior nodes, overflow pages and free pages. */
    private fun populate(): Int {
        open().use { db ->
            val docs = db.bucket("docs")
            repeat(150) { docs.put(key(it), ByteArray(30) { b -> b.toByte() }) }
            docs.put("big-1".toByteArray(), ByteArray(3000) { 1 })
            docs.put("big-2".toByteArray(), ByteArray(2000) { 2 })
            repeat(90) { docs.delete(key(it)) }
            docs.delete("big-2".toByteArray())
            assertTrue(db.pager.freePageCount > 0, "the file should contain free pages")
            db.verify()
            return db.pager.pageCount
        }
    }

    private fun patch(offset: Long, bytes: ByteArray) {
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.write(ByteBuffer.wrap(bytes), offset) }
    }

    private fun assertChecksumMismatch(block: () -> Unit) {
        val failure = assertFailsWith<CorruptDatabaseException> { block() }
        assertTrue("checksum" in failure.message.orEmpty(), "unexpected message: ${failure.message}")
    }

    @Test
    fun `every page ends with a crc of its id and content`() {
        val pages = populate()
        val bytes = Files.readAllBytes(file)
        for (id in 1 until pages) {
            val start = id * pageSize
            val crc = CRC32()
            crc.update(ByteBuffer.allocate(4).putInt(id).array())
            crc.update(bytes, start, pageSize - 4)
            assertEquals(crc.value.toInt(), ByteBuffer.wrap(bytes).getInt(start + pageSize - 4), "page $id")
        }
    }

    @Test
    fun `a flipped byte anywhere in any page is detected by verify`() {
        val pages = populate()
        val original = Files.readAllBytes(file)
        for (id in 1 until pages) {
            val offsets = (0 until pageSize step 29) + (pageSize - 4 until pageSize)
            for (offset in offsets) {
                val position = id.toLong() * pageSize + offset
                patch(position, byteArrayOf((original[position.toInt()].toInt() xor 0x01).toByte()))
                open().use { db -> assertChecksumMismatch { db.verify() } }
                patch(position, byteArrayOf(original[position.toInt()]))
            }
        }
        assertContentEquals(original, Files.readAllBytes(file))
        open().use { it.verify() }
    }

    @Test
    fun `damage in unused page space is caught by the checksum alone`() {
        open().use { it.bucket("b").put("k", "v") }
        val original = Files.readAllBytes(file)
        // Pages 1 and 2 hold a single small cell each, so offset 200 lies in their free gap.
        for (id in 1..2) {
            patch(id.toLong() * pageSize + 200, byteArrayOf(0x7F))
            open().use { db -> assertChecksumMismatch { db.verify() } }
            Files.write(file, original)
        }
    }

    @Test
    fun `a page written to the wrong place is detected`() {
        val pages = populate()
        assertTrue(pages > 4)
        val original = Files.readAllBytes(file)
        val misplaced = original.copyOfRange(2 * pageSize, 3 * pageSize)
        patch(3L * pageSize, misplaced)
        open().use { db -> assertChecksumMismatch { db.verify() } }
    }

    @Test
    fun `reads and writes refuse to use a damaged page and leave the file untouched`() {
        val pages = populate()
        for (id in 1 until pages) patch(id.toLong() * pageSize + 100, byteArrayOf(0x55))
        val damaged = Files.readAllBytes(file)

        open().use { db ->
            assertChecksumMismatch { db.bucket("docs").get(key(100)) }
            assertChecksumMismatch { db.bucket("docs").put(key(1000), byteArrayOf(1)) }
            assertChecksumMismatch { db.transaction { db.bucket("docs").delete(key(100)) } }
            assertTrue(!db.pager.failed)
        }
        assertContentEquals(damaged, Files.readAllBytes(file))
    }

    @Test
    fun `a clean database passes the checksum scan after many reopenings`() {
        populate()
        repeat(5) { round ->
            open().use { db ->
                db.verify()
                db.bucket("docs").put(key(5000 + round), ByteArray(round * 300))
            }
        }
        open().use { it.verify() }
    }
}
