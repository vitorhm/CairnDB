package org.vmessias.cairndb.cli

import org.vmessias.cairndb.Database
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue

/** A mistake of the user of the shell (not a database failure), reported without a stack trace. */
internal class CliException(message: String) : Exception(message)

/**
 * The database as seen by the shell. [Database.transaction] only takes a block, but the shell lets the
 * user type BEGIN and COMMIT as separate lines, so an open transaction is kept alive by a worker thread
 * that sits inside the block executing whatever it is handed. The write lock belongs to that thread, so
 * while a transaction is open everything, reads included, must go through [run].
 */
internal class Session(private val db: Database) : AutoCloseable {
    private var worker: TransactionWorker? = null

    val inTransaction: Boolean get() = worker != null

    fun <T> run(block: (Database) -> T): T {
        val active = worker
        return if (active == null) block(db) else active.submit { block(db) }
    }

    fun begin() {
        if (worker != null) throw CliException("Cannot start a transaction within a transaction")
        worker = TransactionWorker(db).also { it.start() }
    }

    /** Commits; throws if the transaction was doomed by an earlier error, in which case it is rolled back. */
    fun commit() = end(commit = true)

    fun rollback() = end(commit = false)

    private fun end(commit: Boolean) {
        val active = worker ?: throw CliException("No transaction is active")
        worker = null
        active.end(commit)
    }

    override fun close() {
        val active = worker ?: return
        worker = null
        try {
            active.end(commit = false)
        } catch (e: Exception) {
            // The transaction is gone either way; there is nobody left to tell.
        }
    }
}

private class TransactionWorker(private val db: Database) {
    private sealed interface Command {
        class Run(val task: Runnable) : Command
        object Commit : Command
        object Rollback : Command
    }

    private class RollbackRequest : RuntimeException(null, null, false, false)

    private val commands = LinkedBlockingQueue<Command>()
    private val started = CompletableFuture<Unit>()
    private val finished = CompletableFuture<Unit>()
    private val thread = Thread(::loop, "cairndb-transaction").apply { isDaemon = true }

    fun start() {
        thread.start()
        await(started)
    }

    fun <T> submit(block: () -> T): T {
        val result = CompletableFuture<T>()
        commands.put(Command.Run {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        })
        return await(result)
    }

    fun end(commit: Boolean) {
        commands.put(if (commit) Command.Commit else Command.Rollback)
        try {
            await(finished)
        } finally {
            thread.join()
        }
    }

    private fun loop() {
        try {
            db.transaction {
                started.complete(Unit)
                while (true) {
                    when (val command = commands.take()) {
                        is Command.Run -> command.task.run()
                        Command.Commit -> break
                        Command.Rollback -> throw RollbackRequest()
                    }
                }
            }
            finished.complete(Unit)
        } catch (e: RollbackRequest) {
            finished.complete(Unit)
        } catch (t: Throwable) {
            started.completeExceptionally(t)
            finished.completeExceptionally(t)
        }
    }

    private fun <T> await(future: CompletableFuture<T>): T = try {
        future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
