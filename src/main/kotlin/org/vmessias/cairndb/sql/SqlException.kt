package org.vmessias.cairndb.sql

import org.vmessias.cairndb.DatabaseException

/**
 * A statement is malformed or cannot be executed: syntax errors, unknown tables or columns, type
 * and constraint violations.
 *
 * @property position offset in the SQL text where a syntax error was detected, if known.
 */
public class SqlException(message: String, public val position: Int? = null) : DatabaseException(message)
