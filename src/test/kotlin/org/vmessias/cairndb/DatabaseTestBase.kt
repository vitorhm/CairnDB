package org.vmessias.cairndb

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest

/** Base class giving every test a fresh directory and tracking the databases it opens. */
abstract class DatabaseTestBase {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<Database>()

    protected val file: Path get() = dir.resolve("test.db")

    protected fun open(options: DatabaseOptions = DatabaseOptions(pageSize = 512, syncOnCommit = false)): Database =
        Database.open(file, options).also { opened += it }

    @AfterTest
    fun closeAll() {
        opened.forEach { it.close() }
    }

    protected fun key(n: Int): ByteArray = "key-%08d".format(n).toByteArray()

    protected fun scanAll(bucket: Bucket, from: ByteArray? = null, to: ByteArray? = null): List<Entry> =
        bucket.scan(from, to) { it.toList() }
}
