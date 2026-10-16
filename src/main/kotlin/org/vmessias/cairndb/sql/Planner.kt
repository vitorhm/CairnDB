package org.vmessias.cairndb.sql

import java.util.Arrays

/** Resolves column references and parameters of a parsed expression against one table. */
internal class Binder(private val schema: TableSchema?, private val params: List<Any?>) {
    fun bind(expr: Expr): Expr = when (expr) {
        is Param -> Literal(params[expr.index])
        is ColumnRef -> column(expr)
        is Call -> {
            Evaluator.checkCall(expr)
            expr.mapChildren(::bind)
        }
        else -> expr.mapChildren(::bind)
    }

    private fun column(ref: ColumnRef): BoundColumn {
        val table = schema ?: throw SqlException("No such column: ${ref.name}")
        if (ref.qualifier != null && !ref.qualifier.equals(table.name, ignoreCase = true)) {
            throw SqlException("No such table: ${ref.qualifier}")
        }
        val index = table.indexOf(ref.name) ?: throw SqlException("No such column: ${ref.name}")
        return BoundColumn(index, table.columns[index].name)
    }
}

/** How the rows of a table are visited. Whatever the path, the full WHERE condition is still applied to every row. */
internal sealed interface AccessPath {
    fun describe(schema: TableSchema): String

    data object FullScan : AccessPath {
        override fun describe(schema: TableSchema) = "SCAN ${schema.name}"
    }

    /** At most one row: the whole primary key is fixed by equalities. */
    class KeyLookup(val key: ByteArray, private val condition: String) : AccessPath {
        override fun describe(schema: TableSchema) = "SEARCH ${schema.name} USING PRIMARY KEY ($condition)"
    }

    /** The rows with `from <= key < to` (a null bound is open). */
    class KeyRange(val from: ByteArray?, val to: ByteArray?, private val condition: String) : AccessPath {
        override fun describe(schema: TableSchema) = "SEARCH ${schema.name} USING PRIMARY KEY ($condition)"
    }
}

/**
 * Chooses between a full scan and a primary-key search. Conditions of the form `column <op> constant`
 * joined by AND on the leading primary key columns become key bounds: equalities on a prefix of the
 * key, then optionally a range on the next column.
 */
internal object Planner {
    private class Bound(val value: Any, val inclusive: Boolean)

    fun plan(schema: TableSchema, where: Expr?): AccessPath {
        val key = schema.primaryKey
        if (where == null || key.isEmpty()) return AccessPath.FullScan

        val equal = arrayOfNulls<Any>(key.size)
        val lower = List(key.size) { ArrayList<Bound>() }
        val upper = List(key.size) { ArrayList<Bound>() }
        for (condition in conjuncts(where)) {
            for ((op, column, value) in comparisons(condition)) {
                val position = key.indexOf(column)
                if (position < 0) continue
                val usable = keyValue(value, schema.columns[column].type) ?: continue
                when (op) {
                    BinaryOp.EQ -> if (equal[position] == null) equal[position] = usable
                    BinaryOp.GT -> lower[position] += Bound(usable, false)
                    BinaryOp.GE -> lower[position] += Bound(usable, true)
                    BinaryOp.LT -> upper[position] += Bound(usable, false)
                    BinaryOp.LE -> upper[position] += Bound(usable, true)
                    else -> {}
                }
            }
        }

        val prefix = ByteSink()
        val parts = ArrayList<String>()
        var fixed = 0
        while (fixed < key.size && equal[fixed] != null) {
            val column = schema.columns[key[fixed]]
            KeyCodec.append(prefix, equal[fixed]!!, column.type)
            parts += "${column.name}=?"
            fixed++
        }
        val base = prefix.toByteArray()
        if (fixed == key.size) return AccessPath.KeyLookup(base, parts.joinToString(" AND "))

        val column = schema.columns[key[fixed]]
        fun keyOf(bound: Bound) = base + KeyCodec.encode(bound.value, column.type)
        // An exclusive lower bound that has no successor falls back to the inclusive one: a superset, filtered later.
        val froms = lower[fixed].map { b -> keyOf(b).let { if (b.inclusive) it else KeyCodec.prefixEnd(it) ?: it } }
        val tos = upper[fixed].map { b -> keyOf(b).let { if (b.inclusive) KeyCodec.prefixEnd(it) else it } }
        val bestFrom = froms.maxWithOrNull { a, b -> Arrays.compareUnsigned(a, b) }
        val bestTo = tos.filterNotNull().minWithOrNull { a, b -> Arrays.compareUnsigned(a, b) }
        lower[fixed].forEach { parts += "${column.name}${if (it.inclusive) ">=" else ">"}?" }
        upper[fixed].forEach { parts += "${column.name}${if (it.inclusive) "<=" else "<"}?" }

        if (fixed == 0 && bestFrom == null && tos.isEmpty()) return AccessPath.FullScan
        val from = bestFrom ?: base.takeIf { fixed > 0 }
        // Without a tighter upper bound the range ends where the equal-prefix ends (open when there is none).
        val to = bestTo ?: KeyCodec.prefixEnd(base)
        return AccessPath.KeyRange(from, to, parts.joinToString(" AND "))
    }

    private fun conjuncts(expr: Expr): List<Expr> =
        if (expr is Binary && expr.op == BinaryOp.AND) conjuncts(expr.left) + conjuncts(expr.right) else listOf(expr)

    /** `column <op> constant` forms of a condition, with the column on the left. */
    private fun comparisons(condition: Expr): List<Triple<BinaryOp, Int, Any>> {
        if (condition is Between && !condition.negated && condition.operand is BoundColumn) {
            val low = (condition.low as? Literal)?.value
            val high = (condition.high as? Literal)?.value
            return listOfNotNull(
                low?.let { Triple(BinaryOp.GE, condition.operand.index, it) },
                high?.let { Triple(BinaryOp.LE, condition.operand.index, it) },
            )
        }
        if (condition !is Binary) return emptyList()
        val (left, right) = condition.left to condition.right
        return when {
            left is BoundColumn && right is Literal && right.value != null -> listOf(Triple(condition.op, left.index, right.value))
            right is BoundColumn && left is Literal && left.value != null -> listOf(Triple(flip(condition.op), right.index, left.value))
            else -> emptyList()
        }
    }

    private fun flip(op: BinaryOp): BinaryOp = when (op) {
        BinaryOp.LT -> BinaryOp.GT
        BinaryOp.LE -> BinaryOp.GE
        BinaryOp.GT -> BinaryOp.LT
        BinaryOp.GE -> BinaryOp.LE
        else -> op
    }

    /** The constant as a value of the column's own type, or null when comparing needs more than a key lookup. */
    private fun keyValue(value: Any, type: ColumnType): Any? {
        val v = Values.normalize(value)
        return when (type) {
            ColumnType.INTEGER -> v as? Long
            ColumnType.REAL -> when {
                v is Double && !v.isNaN() -> v
                v is Long && v in -EXACT_DOUBLE..EXACT_DOUBLE -> v.toDouble()
                else -> null
            }
            ColumnType.TEXT -> (v as? String)?.takeIf(Values::isWellFormed)
            ColumnType.BLOB -> v as? ByteArray
        }
    }

    private const val EXACT_DOUBLE = 1L shl 53
}
