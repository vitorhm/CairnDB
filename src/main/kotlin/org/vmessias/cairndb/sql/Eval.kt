package org.vmessias.cairndb.sql

import java.util.Locale

/** What an expression is evaluated against: a table row, or the aggregates and group values of a group. */
internal class EvalContext(
    val row: Array<Any?>? = null,
    val aggregates: List<Any?>? = null,
    val groups: List<Any?>? = null,
)

internal object Evaluator {
    val NO_ROW = EvalContext()

    fun eval(expr: Expr, ctx: EvalContext): Any? = when (expr) {
        is Literal -> expr.value
        is BoundColumn -> (ctx.row ?: throw IllegalStateException("No row to read column ${expr.name} from"))[expr.index]
        is AggregateRef -> ctx.aggregates!![expr.index]
        is GroupRef -> ctx.groups!![expr.index]
        is Param, is ColumnRef -> throw IllegalStateException("Unbound expression $expr")
        is Unary -> unary(expr, ctx)
        is Binary -> binary(expr, ctx)
        is IsNull -> (eval(expr.operand, ctx) == null) != expr.negated
        is Like -> like(expr, ctx)
        is InList -> inList(expr, ctx)
        is Between -> between(expr, ctx)
        is Call -> call(expr, ctx)
    }

    /** True only when the condition evaluates to true; NULL (unknown) and false both reject. */
    fun matches(condition: Expr?, ctx: EvalContext): Boolean =
        condition == null || Values.truth(eval(condition, ctx)) == true

    private fun unary(expr: Unary, ctx: EvalContext): Any? {
        val value = Values.normalize(eval(expr.operand, ctx))
        return when (expr.op) {
            "NOT" -> Values.truth(value)?.not()
            else -> when (value) {
                null -> null
                is Long -> if (value == Long.MIN_VALUE) overflow() else -value
                is Double -> -value
                else -> throw SqlException("Cannot negate a ${Values.typeName(value)} value")
            }
        }
    }

    private fun binary(expr: Binary, ctx: EvalContext): Any? {
        when (expr.op) {
            BinaryOp.AND -> {
                val left = Values.truth(eval(expr.left, ctx))
                if (left == false) return false
                val right = Values.truth(eval(expr.right, ctx))
                return if (right == false) false else if (left == null || right == null) null else true
            }
            BinaryOp.OR -> {
                val left = Values.truth(eval(expr.left, ctx))
                if (left == true) return true
                val right = Values.truth(eval(expr.right, ctx))
                return if (right == true) true else if (left == null || right == null) null else false
            }
            else -> {}
        }
        val left = Values.normalize(eval(expr.left, ctx))
        val right = Values.normalize(eval(expr.right, ctx))
        if (left == null || right == null) return null
        return when (expr.op) {
            BinaryOp.EQ -> Values.compare(left, right) == 0
            BinaryOp.NE -> Values.compare(left, right) != 0
            BinaryOp.LT -> Values.compare(left, right) < 0
            BinaryOp.LE -> Values.compare(left, right) <= 0
            BinaryOp.GT -> Values.compare(left, right) > 0
            BinaryOp.GE -> Values.compare(left, right) >= 0
            BinaryOp.CONCAT -> text(left, "||") + text(right, "||")
            else -> arithmetic(expr.op, left, right)
        }
    }

    private fun text(value: Any, operator: String): String = when (value) {
        is String -> value
        is Long, is Double -> value.toString()
        else -> throw SqlException("Operator $operator cannot be applied to a ${Values.typeName(value)} value")
    }

    private fun arithmetic(op: BinaryOp, left: Any, right: Any): Any {
        if (left !is Long && left !is Double || right !is Long && right !is Double) {
            throw SqlException("Operator ${op.symbol} needs numbers but got ${Values.typeName(left)} and ${Values.typeName(right)}")
        }
        if (left is Long && right is Long) {
            return try {
                when (op) {
                    BinaryOp.ADD -> Math.addExact(left, right)
                    BinaryOp.SUB -> Math.subtractExact(left, right)
                    BinaryOp.MUL -> Math.multiplyExact(left, right)
                    BinaryOp.DIV -> if (right == 0L) divisionByZero() else if (left == Long.MIN_VALUE && right == -1L) overflow() else left / right
                    else -> if (right == 0L) divisionByZero() else if (right == -1L) 0L else left % right
                }
            } catch (e: ArithmeticException) {
                overflow()
            }
        }
        val a = (left as Number).toDouble()
        val b = (right as Number).toDouble()
        return when (op) {
            BinaryOp.ADD -> a + b
            BinaryOp.SUB -> a - b
            BinaryOp.MUL -> a * b
            BinaryOp.DIV -> if (b == 0.0) divisionByZero() else a / b
            else -> if (b == 0.0) divisionByZero() else a % b
        }
    }

    private fun overflow(): Nothing = throw SqlException("Integer overflow")

    private fun divisionByZero(): Nothing = throw SqlException("Division by zero")

    private fun like(expr: Like, ctx: EvalContext): Any? {
        val value = eval(expr.operand, ctx)
        val pattern = eval(expr.pattern, ctx)
        if (value == null || pattern == null) return null
        if (value !is String || pattern !is String) {
            throw SqlException("LIKE needs TEXT operands but got ${Values.typeName(value)} and ${Values.typeName(pattern)}")
        }
        return Values.like(value, pattern) != expr.negated
    }

    private fun inList(expr: InList, ctx: EvalContext): Any? {
        val value = eval(expr.operand, ctx) ?: return null
        var sawNull = false
        for (item in expr.items) {
            val candidate = eval(item, ctx)
            if (candidate == null) sawNull = true else if (Values.compare(value, candidate) == 0) return !expr.negated
        }
        return if (sawNull) null else expr.negated
    }

    private fun between(expr: Between, ctx: EvalContext): Any? {
        val value = eval(expr.operand, ctx)
        val low = eval(expr.low, ctx)
        val high = eval(expr.high, ctx)
        val aboveLow = if (value == null || low == null) null else Values.compare(value, low) >= 0
        val belowHigh = if (value == null || high == null) null else Values.compare(value, high) <= 0
        val inside = when {
            aboveLow == false || belowHigh == false -> false
            aboveLow == null || belowHigh == null -> null
            else -> true
        }
        return if (expr.negated) inside?.not() else inside
    }

    private fun call(expr: Call, ctx: EvalContext): Any? {
        val args = expr.args.map { Values.normalize(eval(it, ctx)) }
        return when (expr.name) {
            "LOWER" -> textOrNull(args[0], "LOWER")?.lowercase(Locale.ROOT)
            "UPPER" -> textOrNull(args[0], "UPPER")?.uppercase(Locale.ROOT)
            "LENGTH" -> when (val v = args[0]) {
                null -> null
                is String -> v.codePointCount(0, v.length).toLong()
                is ByteArray -> v.size.toLong()
                else -> throw SqlException("LENGTH needs TEXT or BLOB but got ${Values.typeName(v)}")
            }
            "ABS" -> when (val v = args[0]) {
                null -> null
                is Long -> if (v == Long.MIN_VALUE) overflow() else Math.abs(v)
                is Double -> Math.abs(v)
                else -> throw SqlException("ABS needs a number but got ${Values.typeName(v)}")
            }
            "COALESCE" -> args.firstOrNull { it != null }
            "IFNULL" -> args[0] ?: args[1]
            "TYPEOF" -> Values.typeName(args[0]).lowercase(Locale.ROOT)
            else -> throw SqlException("Misuse of aggregate function ${expr.name}()")
        }
    }

    private fun textOrNull(value: Any?, function: String): String? = when (value) {
        null -> null
        is String -> value
        else -> throw SqlException("$function needs TEXT but got ${Values.typeName(value)}")
    }

    /** Checks name and argument count of a scalar or aggregate function call at bind time. */
    fun checkCall(call: Call) {
        val arity = FUNCTION_ARITY[call.name] ?: throw SqlException("Unknown function ${call.name}()")
        val count = call.args.size
        if (call.star) return
        val ok = when {
            call.name == "COALESCE" -> count >= 1
            call.isAggregate() -> count == 1
            else -> count == arity
        }
        if (!ok) throw SqlException("Wrong number of arguments to ${call.name}()")
    }

    private val FUNCTION_ARITY = mapOf(
        "LOWER" to 1, "UPPER" to 1, "LENGTH" to 1, "ABS" to 1, "TYPEOF" to 1, "IFNULL" to 2, "COALESCE" to 1,
        "COUNT" to 1, "SUM" to 1, "AVG" to 1, "MIN" to 1, "MAX" to 1,
    )
}

/** Running state of one aggregate function over the rows of one group. */
internal class Accumulator(private val name: String, private val star: Boolean) {
    private var count = 0L
    private var longSum = 0L
    private var doubleSum = 0.0
    private var sawDouble = false
    private var best: Any? = null

    fun add(value: Any?) {
        if (star) {
            count++
            return
        }
        val v = Values.normalize(value) ?: return
        count++
        when (name) {
            "SUM", "AVG" -> when (v) {
                is Long -> {
                    if (!sawDouble) {
                        try {
                            longSum = Math.addExact(longSum, v)
                        } catch (e: ArithmeticException) {
                            throw SqlException("Integer overflow in $name()")
                        }
                    }
                    doubleSum += v.toDouble()
                }
                is Double -> {
                    sawDouble = true
                    doubleSum += v
                }
                else -> throw SqlException("$name() needs numbers but got ${Values.typeName(v)}")
            }
            "MIN" -> if (best == null || Values.compare(v, best!!) < 0) best = v
            "MAX" -> if (best == null || Values.compare(v, best!!) > 0) best = v
        }
    }

    fun result(): Any? = when (name) {
        "COUNT" -> count
        "SUM" -> if (count == 0L) null else if (sawDouble) doubleSum else longSum
        "AVG" -> if (count == 0L) null else doubleSum / count
        else -> best
    }
}
