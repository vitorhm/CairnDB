package org.vmessias.cairndb.sql

internal class ParsedStatement(val statement: Statement, val parameterCount: Int)

/** Recursive-descent parser for the supported SQL dialect (see the README). */
internal class Parser(private val sql: String) {
    private val tokens = Lexer(sql).tokenize()
    private var pos = 0
    private var parameters = 0

    fun parse(): ParsedStatement {
        val statement = statement()
        while (acceptSymbol(";")) continue
        if (peek().kind != TokenKind.END) fail("Unexpected ${describe(peek())} after the end of the statement")
        return ParsedStatement(statement, parameters)
    }

    // ---- statements

    private fun statement(): Statement = when {
        acceptKeyword("CREATE") -> createTable()
        acceptKeyword("DROP") -> dropTable()
        acceptKeyword("INSERT") -> insert()
        acceptKeyword("SELECT") -> select()
        acceptKeyword("UPDATE") -> update()
        acceptKeyword("DELETE") -> delete()
        acceptKeyword("EXPLAIN") -> {
            val inner = statement()
            if (inner !is Select && inner !is Update && inner !is Delete) {
                fail("EXPLAIN supports SELECT, UPDATE and DELETE only")
            }
            Explain(inner)
        }
        else -> fail("Expected a statement (CREATE, DROP, INSERT, SELECT, UPDATE, DELETE or EXPLAIN) but found ${describe(peek())}")
    }

    private fun createTable(): Statement {
        expectKeyword("TABLE")
        val ifNotExists = acceptKeyword("IF").also { if (it) { expectKeyword("NOT"); expectKeyword("EXISTS") } }
        val name = identifier("a table name")
        expectSymbol("(")
        val columns = ArrayList<ColumnDef>()
        var tablePrimaryKey: List<String>? = null
        do {
            if (acceptKeyword("PRIMARY")) {
                expectKeyword("KEY")
                if (tablePrimaryKey != null) fail("Only one PRIMARY KEY is allowed")
                expectSymbol("(")
                tablePrimaryKey = commaSeparated { identifier("a column name") }
                expectSymbol(")")
            } else {
                columns += columnDefinition()
            }
        } while (acceptSymbol(","))
        expectSymbol(")")
        return CreateTable(name, ifNotExists, columns, tablePrimaryKey ?: emptyList())
    }

    private fun columnDefinition(): ColumnDef {
        val name = identifier("a column name")
        val type = columnType()
        var notNull = false
        var primaryKey = false
        while (true) {
            when {
                acceptKeyword("PRIMARY") -> {
                    expectKeyword("KEY")
                    primaryKey = true
                }
                acceptKeyword("NOT") -> {
                    expectKeyword("NULL")
                    notNull = true
                }
                acceptKeyword("NULL") -> continue
                else -> return ColumnDef(name, type, notNull, primaryKey)
            }
        }
    }

    private fun columnType(): ColumnType {
        val token = peek()
        if (token.kind != TokenKind.IDENTIFIER || (!token.quoted && token.text.uppercase() in RESERVED_WORDS)) {
            fail("Expected a column type (INTEGER, REAL, TEXT or BLOB) but found ${describe(token)}")
        }
        pos++
        val type = when (token.text.uppercase()) {
            "INTEGER", "INT", "BIGINT", "SMALLINT", "TINYINT" -> ColumnType.INTEGER
            "REAL", "FLOAT", "DOUBLE" -> ColumnType.REAL
            "TEXT", "VARCHAR", "CHAR", "STRING" -> ColumnType.TEXT
            "BLOB" -> ColumnType.BLOB
            else -> fail("Unknown column type '${token.text}'", token)
        }
        if (token.text.equals("DOUBLE", ignoreCase = true)) acceptKeyword("PRECISION")
        if (acceptSymbol("(")) {
            // Length and precision arguments are accepted for compatibility and ignored.
            do {
                if (peek().kind != TokenKind.INTEGER) fail("Expected a number but found ${describe(peek())}")
                pos++
            } while (acceptSymbol(","))
            expectSymbol(")")
        }
        return type
    }

    private fun dropTable(): Statement {
        expectKeyword("TABLE")
        val ifExists = acceptKeyword("IF").also { if (it) expectKeyword("EXISTS") }
        return DropTable(identifier("a table name"), ifExists)
    }

    private fun insert(): Statement {
        expectKeyword("INTO")
        val table = identifier("a table name")
        var columns: List<String>? = null
        if (acceptSymbol("(")) {
            columns = commaSeparated { identifier("a column name") }
            expectSymbol(")")
        }
        expectKeyword("VALUES")
        val rows = commaSeparated {
            expectSymbol("(")
            val values = commaSeparated { expression() }
            expectSymbol(")")
            values
        }
        return Insert(table, columns, rows)
    }

    private fun select(): Statement {
        val distinct = acceptKeyword("DISTINCT")
        val items = commaSeparated { selectItem() }
        val table = if (acceptKeyword("FROM")) identifier("a table name") else null
        val where = if (acceptKeyword("WHERE")) expression() else null
        var groupBy: List<Expr> = emptyList()
        if (acceptKeyword("GROUP")) {
            expectKeyword("BY")
            groupBy = commaSeparated { expression() }
        }
        val having = if (acceptKeyword("HAVING")) expression() else null
        var orderBy: List<OrderTerm> = emptyList()
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY")
            orderBy = commaSeparated {
                val expr = expression()
                val descending = if (acceptKeyword("DESC")) true else { acceptKeyword("ASC"); false }
                OrderTerm(expr, descending)
            }
        }
        var limit: Expr? = null
        var offset: Expr? = null
        if (acceptKeyword("LIMIT")) {
            limit = expression()
            if (acceptKeyword("OFFSET")) offset = expression()
        }
        return Select(distinct, items, table, where, groupBy, having, orderBy, limit, offset)
    }

    private fun selectItem(): SelectItem {
        val first = peek()
        if (acceptSymbol("*")) return SelectItem(null, null, "*")
        val expr = expression()
        val text = sql.substring(first.start, tokens[pos - 1].end)
        val alias = if (acceptKeyword("AS")) identifier("an alias") else null
        return SelectItem(expr, alias, text)
    }

    private fun update(): Statement {
        val table = identifier("a table name")
        expectKeyword("SET")
        val assignments = commaSeparated {
            val column = identifier("a column name")
            expectSymbol("=")
            column to expression()
        }
        val where = if (acceptKeyword("WHERE")) expression() else null
        return Update(table, assignments, where)
    }

    private fun delete(): Statement {
        expectKeyword("FROM")
        val table = identifier("a table name")
        val where = if (acceptKeyword("WHERE")) expression() else null
        return Delete(table, where)
    }

    // ---- expressions, from the loosest to the tightest binding

    fun expression(): Expr = orExpression()

    private fun orExpression(): Expr {
        var left = andExpression()
        while (acceptKeyword("OR")) left = Binary(BinaryOp.OR, left, andExpression())
        return left
    }

    private fun andExpression(): Expr {
        var left = notExpression()
        while (acceptKeyword("AND")) left = Binary(BinaryOp.AND, left, notExpression())
        return left
    }

    private fun notExpression(): Expr =
        if (acceptKeyword("NOT")) Unary("NOT", notExpression()) else comparison()

    private fun comparison(): Expr {
        var left = additive()
        while (true) {
            val op = comparisonOperator()
            if (op != null) {
                left = Binary(op, left, additive())
                continue
            }
            left = when {
                acceptKeyword("IS") -> {
                    val negated = acceptKeyword("NOT")
                    expectKeyword("NULL")
                    IsNull(left, negated)
                }
                else -> {
                    val negated = peekKeyword("NOT") && (peekKeyword("LIKE", 1) || peekKeyword("IN", 1) || peekKeyword("BETWEEN", 1))
                    if (negated) pos++
                    when {
                        acceptKeyword("LIKE") -> Like(left, additive(), negated)
                        acceptKeyword("IN") -> {
                            expectSymbol("(")
                            val items = commaSeparated { expression() }
                            expectSymbol(")")
                            InList(left, items, negated)
                        }
                        acceptKeyword("BETWEEN") -> {
                            val low = additive()
                            expectKeyword("AND")
                            Between(left, low, additive(), negated)
                        }
                        else -> return left
                    }
                }
            }
        }
    }

    private fun comparisonOperator(): BinaryOp? {
        val token = peek()
        if (token.kind != TokenKind.SYMBOL) return null
        val op = when (token.text) {
            "=", "==" -> BinaryOp.EQ
            "<>", "!=" -> BinaryOp.NE
            "<" -> BinaryOp.LT
            "<=" -> BinaryOp.LE
            ">" -> BinaryOp.GT
            ">=" -> BinaryOp.GE
            else -> return null
        }
        pos++
        return op
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (true) {
            left = when {
                acceptSymbol("+") -> Binary(BinaryOp.ADD, left, multiplicative())
                acceptSymbol("-") -> Binary(BinaryOp.SUB, left, multiplicative())
                else -> return left
            }
        }
    }

    private fun multiplicative(): Expr {
        var left = concatenation()
        while (true) {
            left = when {
                acceptSymbol("*") -> Binary(BinaryOp.MUL, left, concatenation())
                acceptSymbol("/") -> Binary(BinaryOp.DIV, left, concatenation())
                acceptSymbol("%") -> Binary(BinaryOp.MOD, left, concatenation())
                else -> return left
            }
        }
    }

    private fun concatenation(): Expr {
        var left = unary()
        while (acceptSymbol("||")) left = Binary(BinaryOp.CONCAT, left, unary())
        return left
    }

    private fun unary(): Expr {
        val start = peek()
        if (acceptSymbol("-")) {
            // Folding the sign into an integer literal lets -9223372036854775808 be written.
            val number = peek()
            if (number.kind == TokenKind.INTEGER) {
                pos++
                return Literal(("-" + number.text).toLongOrNull() ?: fail("Integer literal out of range", start))
            }
            return Unary("-", unary())
        }
        if (acceptSymbol("+")) return unary()
        return primary()
    }

    private fun primary(): Expr {
        val token = peek()
        when (token.kind) {
            TokenKind.INTEGER -> {
                pos++
                return Literal(token.text.toLongOrNull() ?: fail("Integer literal out of range", token))
            }
            TokenKind.REAL -> {
                pos++
                val value = token.text.toDouble()
                if (!value.isFinite()) fail("Numeric literal out of range", token)
                return Literal(value)
            }
            TokenKind.STRING -> {
                pos++
                return Literal(token.text)
            }
            TokenKind.BLOB -> {
                pos++
                return Literal(ByteArray(token.text.length / 2) { token.text.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
            }
            TokenKind.PARAM -> {
                pos++
                return Param(parameters++)
            }
            TokenKind.SYMBOL -> {
                if (token.text == "(") {
                    pos++
                    val inner = expression()
                    expectSymbol(")")
                    return inner
                }
            }
            TokenKind.IDENTIFIER -> return identifierExpression(token)
            TokenKind.END -> {}
        }
        fail("Expected an expression but found ${describe(token)}")
    }

    private fun identifierExpression(token: Token): Expr {
        if (!token.quoted) {
            when (token.text.uppercase()) {
                "NULL" -> { pos++; return Literal(null) }
                "TRUE" -> { pos++; return Literal(true) }
                "FALSE" -> { pos++; return Literal(false) }
            }
            if (isSymbol(peek(1), "(") && token.text.uppercase() !in RESERVED_WORDS) return functionCall(token)
        }
        val first = identifier("an expression")
        if (acceptSymbol(".")) return ColumnRef(first, identifier("a column name"))
        return ColumnRef(null, first)
    }

    private fun functionCall(nameToken: Token): Expr {
        pos += 2
        val name = nameToken.text.uppercase()
        if (acceptSymbol("*")) {
            if (name != "COUNT") fail("Only COUNT accepts *", nameToken)
            expectSymbol(")")
            return Call(name, emptyList(), star = true)
        }
        if (acceptSymbol(")")) return Call(name, emptyList())
        val args = commaSeparated { expression() }
        expectSymbol(")")
        return Call(name, args)
    }

    // ---- token helpers

    private fun peek(offset: Int = 0): Token = tokens[minOf(pos + offset, tokens.lastIndex)]

    private fun isKeyword(token: Token, keyword: String) =
        token.kind == TokenKind.IDENTIFIER && !token.quoted && token.text.equals(keyword, ignoreCase = true)

    private fun isSymbol(token: Token, symbol: String) = token.kind == TokenKind.SYMBOL && token.text == symbol

    private fun peekKeyword(keyword: String, offset: Int = 0) = isKeyword(peek(offset), keyword)

    private fun acceptKeyword(keyword: String): Boolean {
        if (!peekKeyword(keyword)) return false
        pos++
        return true
    }

    private fun expectKeyword(keyword: String) {
        if (!acceptKeyword(keyword)) fail("Expected $keyword but found ${describe(peek())}")
    }

    private fun acceptSymbol(symbol: String): Boolean {
        if (!isSymbol(peek(), symbol)) return false
        pos++
        return true
    }

    private fun expectSymbol(symbol: String) {
        if (!acceptSymbol(symbol)) fail("Expected '$symbol' but found ${describe(peek())}")
    }

    private fun identifier(what: String): String {
        val token = peek()
        if (token.kind != TokenKind.IDENTIFIER || (!token.quoted && token.text.uppercase() in RESERVED_WORDS)) {
            fail("Expected $what but found ${describe(token)}")
        }
        pos++
        return token.text
    }

    private fun <T> commaSeparated(item: () -> T): List<T> {
        val items = ArrayList<T>()
        do items += item() while (acceptSymbol(","))
        return items
    }

    private fun describe(token: Token): String = if (token.kind == TokenKind.END) "the end of the statement" else "'${sql.substring(token.start, token.end)}'"

    private fun fail(message: String, at: Token = peek()): Nothing =
        throw SqlException("Syntax error at position ${at.start}: $message", at.start)

}

/** Words that cannot be used as bare identifiers; quote them ("order") to use them as names. */
internal val RESERVED_WORDS: Set<String> = setOf(
    "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "AND", "OR", "NOT", "IS",
    "NULL", "LIKE", "IN", "BETWEEN", "AS", "VALUES", "SET", "INTO", "TRUE", "FALSE", "DISTINCT", "ASC", "DESC",
    "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "TABLE", "EXPLAIN", "PRIMARY",
)
