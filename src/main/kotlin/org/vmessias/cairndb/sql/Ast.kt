package org.vmessias.cairndb.sql

internal enum class BinaryOp(val symbol: String) {
    OR("OR"), AND("AND"),
    EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="),
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
    CONCAT("||"),
}

/** Expression tree. [Param] and [ColumnRef] only exist before binding; [AggregateRef] and [GroupRef] only after grouping. */
internal sealed interface Expr

internal class Literal(val value: Any?) : Expr {
    override fun equals(other: Any?): Boolean = other is Literal && Values.identical(value, other.value)

    override fun hashCode(): Int = if (value is ByteArray) value.contentHashCode() else value.hashCode()
}

internal data class Param(val index: Int) : Expr

internal data class ColumnRef(val qualifier: String?, val name: String) : Expr

internal data class BoundColumn(val index: Int, val name: String) : Expr

internal data class Unary(val op: String, val operand: Expr) : Expr

internal data class Binary(val op: BinaryOp, val left: Expr, val right: Expr) : Expr

internal data class IsNull(val operand: Expr, val negated: Boolean) : Expr

internal data class Like(val operand: Expr, val pattern: Expr, val negated: Boolean) : Expr

internal data class InList(val operand: Expr, val items: List<Expr>, val negated: Boolean) : Expr

internal data class Between(val operand: Expr, val low: Expr, val high: Expr, val negated: Boolean) : Expr

/** A function call; `COUNT(*)` has [star] set and no arguments. */
internal data class Call(val name: String, val args: List<Expr>, val star: Boolean = false) : Expr

internal data class AggregateRef(val index: Int) : Expr

internal data class GroupRef(val index: Int) : Expr

internal val AGGREGATES = setOf("COUNT", "SUM", "AVG", "MIN", "MAX")

internal fun Call.isAggregate(): Boolean = name in AGGREGATES

/** Rebuilds the node with [transform] applied to each direct child. */
internal fun Expr.mapChildren(transform: (Expr) -> Expr): Expr = when (this) {
    is Literal, is Param, is ColumnRef, is BoundColumn, is AggregateRef, is GroupRef -> this
    is Unary -> Unary(op, transform(operand))
    is Binary -> Binary(op, transform(left), transform(right))
    is IsNull -> IsNull(transform(operand), negated)
    is Like -> Like(transform(operand), transform(pattern), negated)
    is InList -> InList(transform(operand), items.map(transform), negated)
    is Between -> Between(transform(operand), transform(low), transform(high), negated)
    is Call -> Call(name, args.map(transform), star)
}

internal fun Expr.anyNode(predicate: (Expr) -> Boolean): Boolean {
    if (predicate(this)) return true
    var found = false
    mapChildren { child ->
        if (!found && child.anyNode(predicate)) found = true
        child
    }
    return found
}

internal fun Expr.containsAggregate(): Boolean = anyNode { it is Call && it.isAggregate() }

internal sealed interface Statement

internal class ColumnDef(val name: String, val type: ColumnType, val notNull: Boolean, val primaryKey: Boolean)

internal class CreateTable(
    val name: String,
    val ifNotExists: Boolean,
    val columns: List<ColumnDef>,
    val primaryKey: List<String>,
) : Statement

internal class DropTable(val name: String, val ifExists: Boolean) : Statement

internal class Insert(val table: String, val columns: List<String>?, val rows: List<List<Expr>>) : Statement

/** One entry of a SELECT list; [expr] is null for `*`. [text] is the source text, used as the default column name. */
internal class SelectItem(val expr: Expr?, val alias: String?, val text: String)

internal class OrderTerm(val expr: Expr, val descending: Boolean)

internal class Select(
    val distinct: Boolean,
    val items: List<SelectItem>,
    val table: String?,
    val where: Expr?,
    val groupBy: List<Expr>,
    val having: Expr?,
    val orderBy: List<OrderTerm>,
    val limit: Expr?,
    val offset: Expr?,
) : Statement

internal class Update(val table: String, val assignments: List<Pair<String, Expr>>, val where: Expr?) : Statement

internal class Delete(val table: String, val where: Expr?) : Statement

internal class Explain(val statement: Statement) : Statement
