package org.vmessias.cairndb.btree

import org.vmessias.cairndb.CorruptDatabaseException
import org.vmessias.cairndb.Entry
import org.vmessias.cairndb.storage.Page
import org.vmessias.cairndb.storage.Pager
import java.util.Arrays

/**
 * A B+Tree stored in a [Pager]. Keys are compared as unsigned byte strings; all entries live in the
 * leaves, which are chained left to right so range scans never climb back up the tree.
 *
 * The root page id never changes: when the root overflows its content is moved into a new child, and
 * when it is left with a single child that child is moved back into it. That lets a tree be referenced
 * from the catalog (or the file header) by a stable page id.
 *
 * Values that do not fit in a quarter of a page are stored in a chain of overflow pages.
 *
 * Instances are cheap and stateless apart from the root id; callers provide the locking.
 */
internal class BTree(private val pager: Pager, val rootId: Int) {
    private val pageSize = pager.pageSize
    private val usableSize = Page.usableSize(pageSize)
    private val overflowPayload = usableSize - OVERFLOW_HEADER_SIZE

    /** Largest leaf cell kept inline; guarantees that at least four cells fit in a node. */
    private val maxInlineCell = (usableSize - Node.HEADER_SIZE) / 4 - 2

    private class PathStep(val node: Node, val index: Int)

    private class Split(val separator: ByteArray, val right: Node)

    fun get(key: ByteArray): ByteArray? {
        val leaf = leafFor(key)
        val index = leaf.search(key)
        return if (index < 0) null else readValue(leaf, index)
    }

    fun put(key: ByteArray, value: ByteArray) {
        require(key.size <= maxKeySize(pageSize)) { "Key is ${key.size} bytes, the maximum is ${maxKeySize(pageSize)}" }
        val path = ArrayList<PathStep>()
        val leaf = findLeaf(key, path)
        var index = leaf.search(key)
        if (index >= 0) {
            releaseValue(leaf, index)
            leaf.removeCell(index)
        } else {
            index = -(index + 1)
        }
        val inlineSize = Node.LEAF_CELL_HEADER + key.size + value.size
        val cell = if (inlineSize <= maxInlineCell) {
            Node.inlineLeafCell(key, value)
        } else {
            Node.overflowLeafCell(key, value.size, writeOverflow(value))
        }
        insert(leaf, index, cell, path)
    }

    fun delete(key: ByteArray): Boolean {
        val path = ArrayList<PathStep>()
        val leaf = findLeaf(key, path)
        val index = leaf.search(key)
        if (index < 0) return false
        releaseValue(leaf, index)
        leaf.removeCell(index)
        rebalance(leaf, path)
        return true
    }

    /** Entries with `from <= key < to`; a null bound means unbounded. */
    fun scan(from: ByteArray?, to: ByteArray?): Iterator<Entry> = Cursor(this, pager, from, to)

    /** Returns every page of the tree, including the root, to the free list. */
    fun destroy() {
        freeSubtree(rootId, 0)
    }

    /**
     * Checks the structural invariants of the tree and reports every page it owns through [visit].
     * Throws [CorruptDatabaseException] on the first inconsistency.
     */
    fun verify(visit: (Int) -> Unit) {
        val leaves = ArrayList<Node>()
        var leafDepth = -1

        fun walk(id: Int, depth: Int, lower: ByteArray?, upper: ByteArray?) {
            if (depth > MAX_DEPTH) throw CorruptDatabaseException("Tree is deeper than $MAX_DEPTH levels")
            visit(id)
            val node = Node.load(pager, id)
            node.validate()
            var previous: ByteArray? = null
            for (i in 0 until node.cellCount) {
                val key = node.key(i)
                if (lower != null && Arrays.compareUnsigned(key, lower) < 0) {
                    throw CorruptDatabaseException("Page $id: key $i is below the lower bound")
                }
                if (upper != null && Arrays.compareUnsigned(key, upper) >= 0) {
                    throw CorruptDatabaseException("Page $id: key $i is not below the upper bound")
                }
                if (previous != null && Arrays.compareUnsigned(key, previous) <= 0) {
                    throw CorruptDatabaseException("Page $id: keys are not strictly increasing at $i")
                }
                previous = key
            }
            if (node.isLeaf) {
                if (leafDepth == -1) leafDepth = depth
                if (leafDepth != depth) throw CorruptDatabaseException("Page $id: leaves are at different depths")
                for (i in 0 until node.cellCount) if (node.isOverflow(i)) verifyOverflow(node, i, visit)
                leaves += node
            } else {
                for (i in 0..node.cellCount) {
                    val childLower = if (i == 0) lower else node.key(i - 1)
                    val childUpper = if (i == node.cellCount) upper else node.key(i)
                    walk(node.childAt(i), depth + 1, childLower, childUpper)
                }
            }
        }

        walk(rootId, 0, null, null)
        leaves.forEachIndexed { i, leaf ->
            val expected = if (i + 1 < leaves.size) leaves[i + 1].id else 0
            if (leaf.link != expected) {
                throw CorruptDatabaseException("Page ${leaf.id}: next leaf is ${leaf.link}, expected $expected")
            }
        }
    }

    /** The leaf that holds [key], or the left-most leaf when [key] is null. */
    fun leafFor(key: ByteArray?): Node = findLeaf(key, null)

    private fun findLeaf(key: ByteArray?, path: MutableList<PathStep>?): Node {
        var node = Node.load(pager, rootId)
        var depth = 0
        while (!node.isLeaf) {
            val index = if (key == null) 0 else node.childIndexFor(key)
            path?.add(PathStep(node, index))
            node = Node.load(pager, node.childAt(index))
            if (++depth > MAX_DEPTH) throw CorruptDatabaseException("Tree is deeper than $MAX_DEPTH levels")
        }
        return node
    }

    internal fun readValue(leaf: Node, index: Int): ByteArray {
        if (!leaf.isOverflow(index)) return leaf.inlineValue(index)
        val length = leaf.valueLength(index)
        if (length < 0) throw CorruptDatabaseException("Page ${leaf.id}: negative value length")
        val value = ByteArray(length)
        var copied = 0
        var next = leaf.firstOverflowPage(index)
        while (copied < length) {
            val page = readOverflowPage(next)
            val chunk = minOf(overflowPayload, length - copied)
            System.arraycopy(page.data, OVERFLOW_HEADER_SIZE, value, copied, chunk)
            copied += chunk
            next = page.buf.getInt(OVERFLOW_NEXT_OFFSET)
        }
        return value
    }

    private fun readOverflowPage(id: Int) = pager.read(id).also {
        if (it.data[0].toInt() != TYPE_OVERFLOW) throw CorruptDatabaseException("Page $id is not an overflow page")
    }

    private fun writeOverflow(value: ByteArray): Int {
        var first = 0
        var previous: Page? = null
        var offset = 0
        while (offset < value.size) {
            val page = pager.allocate()
            page.data[0] = TYPE_OVERFLOW.toByte()
            val chunk = minOf(overflowPayload, value.size - offset)
            System.arraycopy(value, offset, page.data, OVERFLOW_HEADER_SIZE, chunk)
            if (previous == null) first = page.id else previous.buf.putInt(OVERFLOW_NEXT_OFFSET, page.id)
            previous = page
            offset += chunk
        }
        return first
    }

    private fun releaseValue(leaf: Node, index: Int) {
        if (!leaf.isOverflow(index)) return
        var remaining = leaf.valueLength(index)
        var next = leaf.firstOverflowPage(index)
        while (remaining > 0) {
            val page = readOverflowPage(next)
            val following = page.buf.getInt(OVERFLOW_NEXT_OFFSET)
            pager.free(next)
            next = following
            remaining -= overflowPayload
        }
    }

    private fun verifyOverflow(leaf: Node, index: Int, visit: (Int) -> Unit) {
        var remaining = leaf.valueLength(index)
        var next = leaf.firstOverflowPage(index)
        while (remaining > 0) {
            visit(next)
            next = readOverflowPage(next).buf.getInt(OVERFLOW_NEXT_OFFSET)
            remaining -= overflowPayload
        }
        if (next != 0) throw CorruptDatabaseException("Page ${leaf.id}: overflow chain of cell $index is too long")
    }

    private fun freeSubtree(id: Int, depth: Int) {
        if (depth > MAX_DEPTH) throw CorruptDatabaseException("Tree is deeper than $MAX_DEPTH levels")
        val node = Node.load(pager, id)
        if (node.isLeaf) {
            for (i in 0 until node.cellCount) releaseValue(node, i)
        } else {
            for (i in 0..node.cellCount) freeSubtree(node.childAt(i), depth + 1)
        }
        pager.free(id)
    }

    /**
     * Inserts [startCell] at [startIndex] of [start], splitting full nodes upwards along [path]
     * (ancestors, root first) as far as needed.
     */
    private fun insert(start: Node, startIndex: Int, startCell: ByteArray, path: MutableList<PathStep>) {
        var node = start
        var index = startIndex
        var cell = startCell
        while (true) {
            if (node.insertCell(index, cell)) return
            if (path.isEmpty()) {
                val child = Node.create(pager, node.type, node.loadCells(), node.link)
                node.rebuild(TYPE_INTERIOR, emptyList(), child.id)
                path += PathStep(node, 0)
                node = child
            }
            val split = split(node, index, cell)
            val parentStep = path.removeAt(path.lastIndex)
            // The pointer that used to reach the left half now reaches the right half; the new cell
            // (separator, left half) goes right before it.
            parentStep.node.setChildAt(parentStep.index, split.right.id)
            cell = Node.interiorCell(split.separator, node.id)
            node = parentStep.node
            index = parentStep.index
        }
    }

    /** Splits [node] (which keeps the left half) while adding [cell] at [index]. */
    private fun split(node: Node, index: Int, cell: ByteArray): Split {
        val cells = node.loadCells()
        cells.add(index, cell)
        if (node.isLeaf) {
            // Appending past the last key (sequential inserts) keeps the left leaf full.
            val at = if (index == cells.size - 1) cells.size - 1 else splitPoint(cells).coerceIn(1, cells.size - 1)
            val right = Node.create(pager, TYPE_LEAF, cells.subList(at, cells.size), node.link)
            node.rebuild(TYPE_LEAF, cells.subList(0, at), right.id)
            return Split(Node.keyOfCell(cells[at], leaf = true), right)
        }
        val at = (splitPoint(cells) - 1).coerceIn(1, cells.size - 2)
        val right = Node.create(pager, TYPE_INTERIOR, cells.subList(at + 1, cells.size), node.link)
        node.rebuild(TYPE_INTERIOR, cells.subList(0, at), Node.childOfCell(cells[at]))
        return Split(Node.keyOfCell(cells[at], leaf = false), right)
    }

    /** Number of leading cells whose size first reaches half of the total. */
    private fun splitPoint(cells: List<ByteArray>): Int {
        val total = cells.sumOf { it.size + 2 }
        var running = 0
        for (i in cells.indices) {
            running += cells[i].size + 2
            if (running * 2 >= total) return i + 1
        }
        return cells.size
    }

    /** Merges under-filled nodes with a sibling, walking up [path]; lazy: it never redistributes. */
    private fun rebalance(start: Node, path: MutableList<PathStep>) {
        var node = start
        while (path.isNotEmpty()) {
            if (node.usedBytes >= node.capacity / 2) return
            val step = path.removeAt(path.lastIndex)
            val parent = step.node
            if (parent.cellCount == 0) return
            val leftIndex = if (step.index > 0) step.index - 1 else 0
            val left = Node.load(pager, parent.childAt(leftIndex))
            val right = Node.load(pager, parent.childAt(leftIndex + 1))
            if (!merge(left, right, parent.key(leftIndex))) return
            pager.free(right.id)
            parent.removeCell(leftIndex)
            parent.setChildAt(leftIndex, left.id)
            node = parent
        }
        collapseRoot()
    }

    private fun merge(left: Node, right: Node, separator: ByteArray): Boolean {
        val leaf = left.isLeaf
        val pulledDown = if (leaf) 0 else Node.INTERIOR_CELL_HEADER + separator.size + 2
        if (left.usedBytes + right.usedBytes + pulledDown > left.capacity) return false
        val cells = left.loadCells()
        if (!leaf) cells += Node.interiorCell(separator, left.link)
        cells += right.loadCells()
        left.rebuild(left.type, cells, right.link)
        return true
    }

    /** An interior root left with a single child takes that child's content, keeping its own page id. */
    private fun collapseRoot() {
        while (true) {
            val root = Node.load(pager, rootId)
            if (root.isLeaf || root.cellCount > 0) return
            val child = Node.load(pager, root.link)
            root.rebuild(child.type, child.loadCells(), child.link)
            pager.free(child.id)
        }
    }

    companion object {
        private const val MAX_DEPTH = 64
        private const val OVERFLOW_HEADER_SIZE = 8
        private const val OVERFLOW_NEXT_OFFSET = 4

        /** Longest key a tree on pages of [pageSize] bytes accepts. */
        fun maxKeySize(pageSize: Int): Int = pageSize / 8

        fun create(pager: Pager): BTree = BTree(pager, Node.create(pager, TYPE_LEAF).id)
    }
}
