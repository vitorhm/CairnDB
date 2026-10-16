package org.vmessias.cairndb

/** Base class for all errors raised by the database itself. */
public open class DatabaseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The file is not a database, is truncated or its internal structures are inconsistent. */
public class CorruptDatabaseException(message: String) : DatabaseException(message)

/** The database file is already open, in this or in another process. */
public class DatabaseLockedException(message: String, cause: Throwable? = null) :
    DatabaseException(message, cause)
