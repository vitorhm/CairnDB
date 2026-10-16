package org.vmessias.cairndb.btree

import org.vmessias.cairndb.CorruptDatabaseException
import org.vmessias.cairndb.storage.Page
import org.vmessias.cairndb.storage.Pager
import java.nio.ByteBuffer
import java.util.Arrays

internal const val TYPE_LEAF = 1
internal const val TYPE_INTERIOR = 2
internal const val TYPE_OVERFLOW = 3

/**
 * Slotted-page view of a B+Tree node (leaf or interior).
 *
 * ```
 * offset size field
 *      0    1 type (1 = leaf, 2 = interior)
 *      2    2 cell count
 *      4    2 start of the cell area (cells grow downwards from the end of the page)
 *      6    2 bytes lost to fragmentation inside the cell area
 *      8    4 leaf: next leaf page (0 = none) / interior: right-most child
 *     12    * slot array: one u16 cell offset per cell, sorted by key
 * ```
 *
 * Leaf cell:     `keyLen u16 | valueLen u32 | flags u8 | key | value` (or `| first overflow page u32` when
 * the overflow flag is set and the value lives in a chain of overflow pages).
 *
 * Interior cell: `keyLen u16 | left child u32 | key`. Child `i` holds keys `< key[i]`; the right-most
 * child holds keys `>= key[last]`.
 *
 * Every method that changes the page marks it dirty in the [Pager] itself.
 */
internal class Node(private val pager: Pager, val page: Page) {
    private val data: ByteArray = page.data
    private val buf: ByteBuffer = page.buf

    /** End of the area cells may use: the page checksum sits right after it. */
    private val end: Int = page.usableSize

    val id: Int get() = page.id
    val type: Int get() = data[0].toInt()
    val isLeaf: Boolean get() = type == TYPE_LEAF
    val cellCount: Int get() = u16(2)

    /** Bytes available for cells plus slots in an empty node. */
    val capacity: Int get() = end - HEADER_SIZE

    /** Bytes taken by cells plus slots. */
    val usedBytes: Int get() = capacity - freeSpace

    /** Leaf: next leaf page. Interior: right-most child. */
    var link: Int
        get() = buf.getInt(8)
        set(value) {
            pager.markDirty(page)
            buf.putInt(8, value)
        }

    private val contentStart: Int get() = u16(4)
    private val fragmented: Int get() = u16(6)
    private val gap: Int get() = contentStart - (HEADER_SIZE + 2 * cellCount)
    private val freeSpace: Int get() = gap + fragmented

    private fun u16(offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF

    private fun putU16(offset: Int, value: Int) {
        buf.putShort(offset, value.toShort())
    }

    private fun cellOffset(index: Int): Int = u16(HEADER_SIZE + 2 * index)

    private val keyHeader: Int get() = if (isLeaf) LEAF_CELL_HEADER else INTERIOR_CELL_HEADER

    fun keyLength(index: Int): Int = u16(cellOffset(index))

    fun key(index: Int): ByteArray {
        val start = cellOffset(index) + keyHeader
        return data.copyOfRange(start, start + keyLength(index))
    }

    fun compareKey(index: Int, other: ByteArray): Int {
        val start = cellOffset(index) + keyHeader
        return Arrays.compareUnsigned(data, start, start + keyLength(index), other, 0, other.size)
    }

    /** Binary search: the index of [key] if present, otherwise `-(insertion point) - 1`. */
    fun search(key: ByteArray): Int {
        var low = 0
        var high = cellCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareKey(mid, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    /** Interior only: index of the child that may contain [key] (`cellCount` means the right-most child). */
    fun childIndexFor(key: ByteArray): Int {
        var low = 0
        var high = cellCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareKey(mid, key) <= 0) low = mid + 1 else high = mid
        }
        return low
    }

    fun cellSize(index: Int): Int {
        val offset = cellOffset(index)
        val keyLength = u16(offset)
        if (!isLeaf) return INTERIOR_CELL_HEADER + keyLength
        val payload = if (isOverflow(index)) OVERFLOW_POINTER_SIZE else buf.getInt(offset + 2)
        return LEAF_CELL_HEADER + keyLength + payload
    }

    // ---- leaf cells ----

    fun valueLength(index: Int): Int = buf.getInt(cellOffset(index) + 2)

    fun isOverflow(index: Int): Boolean = data[cellOffset(index) + 6].toInt() and FLAG_OVERFLOW != 0

    fun inlineValue(index: Int): ByteArray {
        val start = cellOffset(index) + LEAF_CELL_HEADER + keyLength(index)
        return data.copyOfRange(start, start + valueLength(index))
    }

    fun firstOverflowPage(index: Int): Int = buf.getInt(cellOffset(index) + LEAF_CELL_HEADER + keyLength(index))

    // ---- interior cells ----

    fun childAt(index: Int): Int = if (index == cellCount) link else buf.getInt(cellOffset(index) + 2)

    fun setChildAt(index: Int, child: Int) {
        if (index == cellCount) {
            link = child
        } else {
            pager.markDirty(page)
            buf.putInt(cellOffset(index) + 2, child)
        }
    }

    // ---- mutation ----

    /** Inserts [cell] at slot [index]; returns false (leaving the node untouched) when it does not fit. */
    fun insertCell(index: Int, cell: ByteArray): Boolean {
        val needed = cell.size + 2
        if (freeSpace < needed) return false
        pager.markDirty(page)
        if (gap < needed) defragment()
        val count = cellCount
        val start = contentStart - cell.size
        System.arraycopy(cell, 0, data, start, cell.size)
        val slot = HEADER_SIZE + 2 * index
        System.arraycopy(data, slot, data, slot + 2, 2 * (count - index))
        putU16(slot, start)
        putU16(2, count + 1)
        putU16(4, start)
        return true
    }

    fun removeCell(index: Int) {
        pager.markDirty(page)
        val size = cellSize(index)
        val count = cellCount
        val slot = HEADER_SIZE + 2 * index
        System.arraycopy(data, slot + 2, data, slot, 2 * (count - index - 1))
        putU16(2, count - 1)
        if (count == 1) {
            putU16(4, end)
            putU16(6, 0)
        } else {
            putU16(6, fragmented + size)
        }
    }

    fun loadCells(): MutableList<ByteArray> = MutableList(cellCount) { i ->
        val offset = cellOffset(i)
        data.copyOfRange(offset, offset + cellSize(i))
    }

    /** Replaces the whole content of the page. */
    fun rebuild(type: Int, cells: List<ByteArray>, link: Int) {
        var cellStart = end
        val total = cells.sumOf { it.size + 2 }
        check(total <= capacity) { "Cells do not fit in page ${id}: $total > $capacity" }
        pager.markDirty(page)
        data.fill(0)
        data[0] = type.toByte()
        cells.forEachIndexed { i, cell ->
            cellStart -= cell.size
            System.arraycopy(cell, 0, data, cellStart, cell.size)
            putU16(HEADER_SIZE + 2 * i, cellStart)
        }
        putU16(2, cells.size)
        putU16(4, cellStart)
        putU16(6, 0)
        buf.putInt(8, link)
    }

    /** Checks that slots, cells and the free-space bookkeeping agree; throws [CorruptDatabaseException] if not. */
    fun validate() {
        val count = cellCount
        val start = contentStart
        if (HEADER_SIZE + 2 * count > start || start > end) {
            throw CorruptDatabaseException("Page $id: slot array overlaps the cell area")
        }
        var cellBytes = 0
        for (i in 0 until count) {
            val offset = cellOffset(i)
            if (offset < start || offset + keyHeader > end) {
                throw CorruptDatabaseException("Page $id: cell $i starts outside the cell area")
            }
            val size = cellSize(i)
            if (size < 0 || offset + size > end) throw CorruptDatabaseException("Page $id: cell $i overruns the page")
            cellBytes += size
        }
        if (cellBytes + fragmented != end - start) {
            throw CorruptDatabaseException("Page $id: free-space accounting does not add up")
        }
    }

    private fun defragment() {
        rebuild(type, loadCells(), link)
    }

    companion object {
        const val HEADER_SIZE = 12
        const val LEAF_CELL_HEADER = 7
        const val INTERIOR_CELL_HEADER = 6
        const val OVERFLOW_POINTER_SIZE = 4
        const val FLAG_OVERFLOW = 1

        fun load(pager: Pager, id: Int): Node {
            val page = pager.read(id)
            val type = page.data[0].toInt()
            if (type != TYPE_LEAF && type != TYPE_INTERIOR) {
                throw CorruptDatabaseException("Page $id is not a tree node (type $type)")
            }
            return Node(pager, page)
        }

        fun create(pager: Pager, type: Int, cells: List<ByteArray> = emptyList(), link: Int = 0): Node {
            val node = Node(pager, pager.allocate())
            node.rebuild(type, cells, link)
            return node
        }

        fun inlineLeafCell(key: ByteArray, value: ByteArray): ByteArray {
            val cell = ByteArray(LEAF_CELL_HEADER + key.size + value.size)
            val buf = ByteBuffer.wrap(cell)
            buf.putShort(0, key.size.toShort())
            buf.putInt(2, value.size)
            key.copyInto(cell, LEAF_CELL_HEADER)
            value.copyInto(cell, LEAF_CELL_HEADER + key.size)
            return cell
        }

        fun overflowLeafCell(key: ByteArray, valueLength: Int, firstPage: Int): ByteArray {
            val cell = ByteArray(LEAF_CELL_HEADER + key.size + OVERFLOW_POINTER_SIZE)
            val buf = ByteBuffer.wrap(cell)
            buf.putShort(0, key.size.toShort())
            buf.putInt(2, valueLength)
            cell[6] = FLAG_OVERFLOW.toByte()
            key.copyInto(cell, LEAF_CELL_HEADER)
            buf.putInt(LEAF_CELL_HEADER + key.size, firstPage)
            return cell
        }

        fun interiorCell(key: ByteArray, child: Int): ByteArray {
            val cell = ByteArray(INTERIOR_CELL_HEADER + key.size)
            val buf = ByteBuffer.wrap(cell)
            buf.putShort(0, key.size.toShort())
            buf.putInt(2, child)
            key.copyInto(cell, INTERIOR_CELL_HEADER)
            return cell
        }

        fun keyOfCell(cell: ByteArray, leaf: Boolean): ByteArray {
            val start = if (leaf) LEAF_CELL_HEADER else INTERIOR_CELL_HEADER
            val length = ByteBuffer.wrap(cell).getShort(0).toInt() and 0xFFFF
            return cell.copyOfRange(start, start + length)
        }

        fun childOfCell(cell: ByteArray): Int = ByteBuffer.wrap(cell).getInt(2)
    }
}
