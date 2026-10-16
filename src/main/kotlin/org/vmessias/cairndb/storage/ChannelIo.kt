package org.vmessias.cairndb.storage

import org.vmessias.cairndb.CorruptDatabaseException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Fills the remaining space of [target] from [position], failing if the file ends first. */
internal fun FileChannel.readFully(target: ByteBuffer, position: Long) {
    var pos = position
    while (target.hasRemaining()) {
        val n = read(target, pos)
        if (n < 0) throw CorruptDatabaseException("Unexpected end of file at offset $pos")
        pos += n
    }
}

internal fun FileChannel.readFully(target: ByteArray, position: Long) = readFully(ByteBuffer.wrap(target), position)

internal fun FileChannel.writeFully(source: ByteBuffer, position: Long) {
    var pos = position
    while (source.hasRemaining()) pos += write(source, pos)
}

internal fun FileChannel.writeFully(source: ByteArray, position: Long) = writeFully(ByteBuffer.wrap(source), position)
