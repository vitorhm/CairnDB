package org.vmessias.cairndb

/** A key/value pair returned by [Bucket.scan]. Both arrays are private copies and may be modified freely. */
public class Entry(public val key: ByteArray, public val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Entry && key.contentEquals(other.key) && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * key.contentHashCode() + value.contentHashCode()

    override fun toString(): String = "Entry(key=${key.contentToString()}, value=${value.size} bytes)"
}

/**
 * A named, ordered collection of key/value pairs inside a [Database] (think "table").
 *
 * Keys are ordered as unsigned byte strings. A bucket handle is only a name: it stays valid across
 * reopenings of the database and fails with [DatabaseException] if the bucket has been dropped.
 * Every operation is atomic and may be called from several threads at once. Called inside
 * [Database.transaction] on the same thread, an operation joins that transaction instead of
 * committing by itself.
 */
public class Bucket internal constructor(private val db: Database, public val name: String) {
    /** Returns the value stored under [key], or null. */
    public fun get(key: ByteArray): ByteArray? = db.read { db.treeOf(name).get(key) }

    public fun get(key: String): String? = get(key.toByteArray())?.decodeToString()

    /** Stores [value] under [key], replacing any previous value. */
    public fun put(key: ByteArray, value: ByteArray) {
        db.checkKey(key)
        db.write { db.treeOf(name).put(key, value) }
    }

    public fun put(key: String, value: String): Unit = put(key.toByteArray(), value.toByteArray())

    /** Removes [key]; returns whether it was present. */
    public fun delete(key: ByteArray): Boolean = db.write { db.treeOf(name).delete(key) }

    public fun delete(key: String): Boolean = delete(key.toByteArray())

    public fun contains(key: ByteArray): Boolean = get(key) != null

    /**
     * Runs [block] over the entries with `from <= key < to` in key order; a null bound is open.
     *
     * The sequence is lazy and only valid inside [block], which runs under a read lock: other threads
     * can read meanwhile but writes wait for it to return, and writing from inside [block] throws.
     */
    public fun <T> scan(from: ByteArray? = null, to: ByteArray? = null, block: (Sequence<Entry>) -> T): T =
        db.read(scan = true) {
            val iterator = db.treeOf(name).scan(from, to)
            block(Sequence { iterator })
        }

    /** Number of entries; walks the whole bucket. */
    public fun count(): Long = scan { entries -> entries.count().toLong() }
}
