package org.vmessias.cairndb.btree

import org.vmessias.cairndb.Entry
import org.vmessias.cairndb.storage.Pager
import java.util.Arrays

/** Forward iterator over the leaves of a [BTree], bounded by an exclusive upper key. */
internal class Cursor(
    private val tree: BTree,
    private val pager: Pager,
    from: ByteArray?,
    private val to: ByteArray?,
) : Iterator<Entry> {
    private var leaf: Node? = tree.leafFor(from)
    private var index: Int = from?.let { start ->
        leaf!!.search(start).let { if (it >= 0) it else -(it + 1) }
    } ?: 0

    override fun hasNext(): Boolean {
        var current = leaf ?: return false
        while (index >= current.cellCount) {
            val next = current.link
            if (next == 0) {
                leaf = null
                return false
            }
            pager.trim()
            current = Node.load(pager, next)
            leaf = current
            index = 0
        }
        if (to != null && current.compareKey(index, to) >= 0) {
            leaf = null
            return false
        }
        return true
    }

    override fun next(): Entry {
        if (!hasNext()) throw NoSuchElementException()
        val current = leaf!!
        val entry = Entry(current.key(index), tree.readValue(current, index))
        index++
        return entry
    }
}
