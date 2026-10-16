package org.vmessias.cairndb

/**
 * Tuning knobs for [Database.open].
 *
 * @property pageSize size in bytes of every page; a power of two between 512 and 32768.
 *   Only used when the file is created, an existing file keeps the size it was created with.
 * @property cachePages how many clean pages are kept in memory between operations.
 * @property syncOnCommit when true every commit (a write operation or a whole transaction) is flushed
 *   with `fsync` at each journal step before it returns. When false, a process crash is still
 *   recovered, but a power loss or OS crash may lose recent commits or corrupt the file.
 */
public data class DatabaseOptions(
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val cachePages: Int = DEFAULT_CACHE_PAGES,
    val syncOnCommit: Boolean = true,
) {
    init {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE && Integer.bitCount(pageSize) == 1) {
            "pageSize must be a power of two between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE, got $pageSize"
        }
        require(cachePages >= MIN_CACHE_PAGES) { "cachePages must be at least $MIN_CACHE_PAGES, got $cachePages" }
    }

    public companion object {
        public const val DEFAULT_PAGE_SIZE: Int = 4096
        public const val MIN_PAGE_SIZE: Int = 512
        public const val MAX_PAGE_SIZE: Int = 32768
        public const val DEFAULT_CACHE_PAGES: Int = 1024
        public const val MIN_CACHE_PAGES: Int = 8
    }
}
