package org.vmessias.cairndb.sql

import org.vmessias.cairndb.Bucket
import org.vmessias.cairndb.CorruptDatabaseException
import org.vmessias.cairndb.Database
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Executes parsed statements against a [Database]. A query runs under the read lock, anything else
 * in one transaction, so every statement is atomic; inside a user transaction both simply join it.
 */
internal class SqlEngine(private val db: Database) {
    fun execute(sql: String, rawParams: Array<out Any?>): SqlResult {
        val parsed = Parser(sql).parse()
        val params = rawParams.map(::normalizeParam)
        if (params.size != parsed.parameterCount) {
            throw SqlException("The statement has ${parsed.parameterCount} parameter(s) but ${params.size} value(s) were given")
        }
        return when (val statement = parsed.statement) {
            is Select -> db.read { select(statement, params) }
            is Explain -> db.read { explain(statement.statement, params) }
            else -> db.transaction {
                when (statement) {
                    is CreateTable -> createTable(statement)
                    is DropTable -> dropTable(statement)
                    is Insert -> insert(statement, params)
                    is Update -> update(statement, params)
                    is Delete -> delete(statement, params)
                }
            }
        }
    }

    // ---- catalog

    private fun schemaKey(name: String): ByteArray = name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)

    private fun findTable(name: String): TableSchema? {
        val bucket = db.bucketOrNull(SCHEMA_BUCKET) ?: return null
        return bucket.get(schemaKey(name))?.let(SchemaCodec::decode)
    }

    private fun table(name: String): TableSchema = findTable(name) ?: throw SqlException("No such table: $name")

    fun tables(): List<TableInfo> = db.read {
        db.bucketOrNull(SCHEMA_BUCKET)
            ?.scan { entries -> entries.map { TableInfo.of(SchemaCodec.decode(it.value)) }.toList() }
            ?: emptyList()
    }

    fun tableInfo(name: String): TableInfo? = db.read { findTable(name)?.let(TableInfo::of) }

    private fun rowsOf(schema: TableSchema): Bucket =
        db.bucketOrNull(schema.bucketName) ?: throw CorruptDatabaseException("The data of table ${schema.name} is missing")

    private fun saveSchema(schema: TableSchema) {
        db.bucket(SCHEMA_BUCKET).put(schemaKey(schema.name), SchemaCodec.encode(schema))
    }

    // ---- DDL

    private fun createTable(statement: CreateTable): SqlResult {
        if (!Values.isWellFormed(statement.name) || statement.columns.any { !Values.isWellFormed(it.name) }) {
            throw SqlException("Identifiers cannot contain unpaired surrogates")
        }
        if (findTable(statement.name) != null) {
            if (statement.ifNotExists) return EMPTY
            throw SqlException("Table already exists: ${statement.name}")
        }
        val definitions = statement.columns
        val names = HashSet<String>()
        for (column in definitions) {
            if (!names.add(column.name.lowercase(Locale.ROOT))) throw SqlException("Duplicate column name: ${column.name}")
        }
        val inline = definitions.indices.filter { definitions[it].primaryKey }
        if (inline.isNotEmpty() && statement.primaryKey.isNotEmpty() || inline.size > 1) {
            throw SqlException("Table ${statement.name} has more than one PRIMARY KEY")
        }
        val key = if (statement.primaryKey.isNotEmpty()) {
            statement.primaryKey.map { name ->
                definitions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                    .also { if (it < 0) throw SqlException("Unknown column in PRIMARY KEY: $name") }
            }
        } else {
            inline
        }
        if (key.toSet().size != key.size) throw SqlException("A column appears twice in the PRIMARY KEY")
        val columns = definitions.mapIndexed { index, d -> Column(d.name, d.type, d.notNull || index in key) }
        val schema = TableSchema(statement.name, columns, key)
        if (schema.bucketName.toByteArray(Charsets.UTF_8).size > db.maxKeySize) {
            throw SqlException("Table name is too long: ${statement.name}")
        }
        db.bucket(schema.bucketName)
        saveSchema(schema)
        return EMPTY
    }

    private fun dropTable(statement: DropTable): SqlResult {
        val schema = findTable(statement.name)
        if (schema == null) {
            if (statement.ifExists) return EMPTY
            throw SqlException("No such table: ${statement.name}")
        }
        db.dropBucket(schema.bucketName)
        db.bucket(SCHEMA_BUCKET).delete(schemaKey(schema.name))
        return EMPTY
    }

    // ---- INSERT

    private fun insert(statement: Insert, params: List<Any?>): SqlResult {
        val schema = table(statement.table)
        val targets = statement.columns?.map { name ->
            schema.indexOf(name) ?: throw SqlException("No such column: $name")
        } ?: schema.columns.indices.toList()
        if (targets.toSet().size != targets.size) throw SqlException("A column is listed twice")

        val binder = Binder(null, params)
        val bucket = rowsOf(schema)
        val firstRowId = schema.nextRowId
        val seenKeys = HashSet<ByteBuffer>()
        val pending = ArrayList<Pair<ByteArray, ByteArray>>()
        var lastId: Long? = null

        for (expressions in statement.rows) {
            if (expressions.size != targets.size) {
                throw SqlException("Expected ${targets.size} value(s) but got ${expressions.size}")
            }
            val row = arrayOfNulls<Any?>(schema.columns.size)
            expressions.forEachIndexed { i, expression ->
                row[targets[i]] = Evaluator.eval(binder.bind(expression), Evaluator.NO_ROW)
            }
            val auto = schema.autoKeyColumn
            if (auto != null && row[auto] == null) row[auto] = allocateRowId(schema)
            coerceRow(schema, row)

            val key: ByteArray
            if (schema.hasHiddenRowId) {
                val id = allocateRowId(schema)
                key = KeyCodec.rowId(id)
                lastId = id
            } else {
                key = KeyCodec.primaryKey(schema, row)
                if (auto != null) {
                    val id = row[auto] as Long
                    if (id >= schema.nextRowId) schema.nextRowId = if (id == Long.MAX_VALUE) id else id + 1
                    lastId = id
                }
            }
            checkKeySize(key)
            if (!seenKeys.add(ByteBuffer.wrap(key)) || bucket.get(key) != null) throw uniqueViolation(schema)
            pending += key to RowCodec.encode(row)
        }
        for ((key, value) in pending) bucket.put(key, value)
        if (schema.nextRowId != firstRowId) saveSchema(schema)
        return SqlResult(emptyList(), emptyList(), rowsAffected = pending.size, lastInsertId = lastId)
    }

    private fun allocateRowId(schema: TableSchema): Long {
        val id = schema.nextRowId
        if (id == Long.MAX_VALUE) throw SqlException("Row ids of table ${schema.name} are exhausted")
        schema.nextRowId = id + 1
        return id
    }

    private fun checkKeySize(key: ByteArray) {
        if (key.size > db.maxKeySize) {
            throw SqlException("The primary key takes ${key.size} bytes once encoded; the maximum is ${db.maxKeySize}")
        }
    }

    private fun uniqueViolation(schema: TableSchema): SqlException {
        val columns = schema.primaryKey.ifEmpty { return SqlException("Row id collision in table ${schema.name}") }
        return SqlException("UNIQUE constraint failed: " + columns.joinToString(", ") { "${schema.name}.${schema.columns[it].name}" })
    }

    private fun coerceRow(schema: TableSchema, row: Array<Any?>) {
        for (i in row.indices) row[i] = coerce(row[i], schema.columns[i], schema.name)
    }

    private fun coerce(raw: Any?, column: Column, table: String): Any? {
        val value = Values.normalize(raw)
        if (value == null) {
            if (column.notNull) throw SqlException("NOT NULL constraint failed: $table.${column.name}")
            return null
        }
        val coerced: Any? = when (column.type) {
            ColumnType.INTEGER -> value as? Long
            ColumnType.REAL -> when (value) {
                is Double -> if (value.isNaN()) throw SqlException("NaN cannot be stored in $table.${column.name}") else if (value == 0.0) 0.0 else value
                is Long -> value.toDouble()
                else -> null
            }
            ColumnType.TEXT -> (value as? String)?.also {
                if (!Values.isWellFormed(it)) throw SqlException("TEXT value for $table.${column.name} contains an unpaired surrogate")
            }
            ColumnType.BLOB -> value as? ByteArray
        }
        return coerced ?: throw SqlException(
            "Cannot store a ${Values.typeName(value)} value in the ${column.type} column $table.${column.name}",
        )
    }

    // ---- UPDATE and DELETE

    private class Match(val key: ByteArray, val row: Array<Any?>)

    private class Change(val oldKey: ByteArray, val newKey: ByteArray, val newValue: ByteArray)

    private fun update(statement: Update, params: List<Any?>): SqlResult {
        val schema = table(statement.table)
        val binder = Binder(schema, params)
        val assignments = statement.assignments.map { (name, expression) ->
            val index = schema.indexOf(name) ?: throw SqlException("No such column: $name")
            val bound = binder.bind(expression)
            if (bound.containsAggregate()) throw SqlException("Aggregate functions are not allowed in UPDATE")
            index to bound
        }
        if (assignments.map { it.first }.toSet().size != assignments.size) throw SqlException("A column is assigned twice")
        val where = bindCondition(binder, statement.where)
        val bucket = rowsOf(schema)
        val matches = collect(schema, bucket, where)
        val keyChanges = !schema.hasHiddenRowId && assignments.any { it.first in schema.primaryKey }

        val changes = matches.map { match ->
            val context = EvalContext(match.row)
            val row = match.row.copyOf()
            for ((index, expression) in assignments) row[index] = Evaluator.eval(expression, context)
            coerceRow(schema, row)
            val key = if (keyChanges) KeyCodec.primaryKey(schema, row).also(::checkKeySize) else match.key
            Change(match.key, key, RowCodec.encode(row))
        }

        val moved = changes.filter { !it.newKey.contentEquals(it.oldKey) }
        val vacated = moved.mapTo(HashSet()) { ByteBuffer.wrap(it.oldKey) }
        val claimed = HashSet<ByteBuffer>()
        for (change in moved) {
            val target = ByteBuffer.wrap(change.newKey)
            if (!claimed.add(target) || (target !in vacated && bucket.get(change.newKey) != null)) throw uniqueViolation(schema)
        }

        for (change in moved) bucket.delete(change.oldKey)
        for (change in changes) bucket.put(change.newKey, change.newValue)

        val auto = schema.autoKeyColumn
        if (auto != null && assignments.any { it.first == auto }) {
            val before = schema.nextRowId
            for (change in changes) {
                val id = decodeAuto(schema, change.newValue, auto)
                if (id >= schema.nextRowId) schema.nextRowId = if (id == Long.MAX_VALUE) id else id + 1
            }
            if (schema.nextRowId != before) saveSchema(schema)
        }
        return SqlResult(emptyList(), emptyList(), rowsAffected = changes.size)
    }

    private fun decodeAuto(schema: TableSchema, value: ByteArray, column: Int): Long =
        RowCodec.decode(value, schema.columns.size)[column] as Long

    private fun delete(statement: Delete, params: List<Any?>): SqlResult {
        val schema = table(statement.table)
        val where = bindCondition(Binder(schema, params), statement.where)
        val bucket = rowsOf(schema)
        val matches = collect(schema, bucket, where)
        for (match in matches) bucket.delete(match.key)
        return SqlResult(emptyList(), emptyList(), rowsAffected = matches.size)
    }

    private fun bindCondition(binder: Binder, condition: Expr?): Expr? {
        val bound = condition?.let(binder::bind)
        if (bound?.containsAggregate() == true) throw SqlException("Aggregate functions are not allowed in WHERE")
        return bound
    }

    /** Rows satisfying [where], read completely before anything is modified. */
    private fun collect(schema: TableSchema, bucket: Bucket, where: Expr?): List<Match> {
        val matches = ArrayList<Match>()
        scanRows(schema, bucket, Planner.plan(schema, where), where) { row, key ->
            matches += Match(key, row)
            true
        }
        return matches
    }

    /** Visits the rows reached by [path] that satisfy [where]; [visit] returns false to stop. */
    private fun scanRows(
        schema: TableSchema,
        bucket: Bucket,
        path: AccessPath,
        where: Expr?,
        visit: (row: Array<Any?>, key: ByteArray) -> Boolean,
    ) {
        fun consider(key: ByteArray, value: ByteArray): Boolean {
            val row = RowCodec.decode(value, schema.columns.size)
            return !Evaluator.matches(where, EvalContext(row)) || visit(row, key)
        }
        when (path) {
            AccessPath.FullScan -> bucket.scan(null, null) { entries ->
                for (entry in entries) if (!consider(entry.key, entry.value)) break
            }
            is AccessPath.KeyLookup -> bucket.get(path.key)?.let { consider(path.key, it) }
            is AccessPath.KeyRange -> bucket.scan(path.from, path.to) { entries ->
                for (entry in entries) if (!consider(entry.key, entry.value)) break
            }
        }
    }

    // ---- SELECT

    private class Output(val values: List<Any?>, val sortKeys: List<Any?>)

    private class OrderKey(val item: Int?, val expr: Expr?, val descending: Boolean)

    private class Group(val keyValues: List<Any?>, val accumulators: List<Accumulator>)

    private fun select(statement: Select, params: List<Any?>): SqlResult {
        val schema = statement.table?.let(::table)
        val binder = Binder(schema, params)

        val names = ArrayList<String>()
        val projections = ArrayList<Expr>()
        val aliases = HashMap<String, Int>()
        for (item in statement.items) {
            val expression = item.expr
            if (expression == null) {
                if (schema == null) throw SqlException("SELECT * needs a FROM clause")
                schema.columns.forEachIndexed { i, column ->
                    projections += BoundColumn(i, column.name)
                    names += column.name
                }
            } else {
                projections += binder.bind(expression)
                names += item.alias ?: (expression as? ColumnRef)?.name ?: item.text
                item.alias?.let { aliases[it.lowercase(Locale.ROOT)] = projections.lastIndex }
            }
        }

        val where = bindCondition(binder, statement.where)
        val groupBy = statement.groupBy.map { term ->
            val bound = when {
                term is Literal && term.value is Long -> projections[ordinal(term.value, projections.size, "GROUP BY")]
                term is ColumnRef && term.qualifier == null && schema?.indexOf(term.name) == null && term.name.lowercase(Locale.ROOT) in aliases ->
                    projections[aliases.getValue(term.name.lowercase(Locale.ROOT))]
                else -> binder.bind(term)
            }
            if (bound.containsAggregate()) throw SqlException("Aggregate functions are not allowed in GROUP BY")
            bound
        }
        val having = statement.having?.let(binder::bind)
        val order = statement.orderBy.map { term ->
            val expression = term.expr
            when {
                expression is Literal && expression.value is Long ->
                    OrderKey(ordinal(expression.value, projections.size, "ORDER BY"), null, term.descending)
                expression is ColumnRef && expression.qualifier == null && expression.name.lowercase(Locale.ROOT) in aliases ->
                    OrderKey(aliases.getValue(expression.name.lowercase(Locale.ROOT)), null, term.descending)
                else -> OrderKey(null, binder.bind(expression), term.descending)
            }
        }

        val aggregating = groupBy.isNotEmpty() || having != null || projections.any { it.containsAggregate() } ||
            order.any { it.expr?.containsAggregate() == true }
        val aggregates = ArrayList<Call>()
        val outputs: List<Expr>
        val havingExpr: Expr?
        val orderKeys: List<OrderKey>
        if (aggregating) {
            outputs = projections.map { rewriteForGroups(it, groupBy, aggregates) }
            havingExpr = having?.let { rewriteForGroups(it, groupBy, aggregates) }
            orderKeys = order.map { key -> key.expr?.let { OrderKey(null, rewriteForGroups(it, groupBy, aggregates), key.descending) } ?: key }
        } else {
            outputs = projections
            havingExpr = null
            orderKeys = order
        }

        val limit = statement.limit?.let { constant(binder, it, "LIMIT") }
        val offset = statement.offset?.let { constant(binder, it, "OFFSET") } ?: 0L
        // Without sorting, grouping or deduplication the scan can stop as soon as enough rows are known.
        val needed = if (limit != null && !aggregating && !statement.distinct && orderKeys.isEmpty() && limit <= Long.MAX_VALUE - offset) offset + limit else null

        val results = ArrayList<Output>()
        fun emit(context: EvalContext): Boolean {
            val values = outputs.map { Values.normalize(Evaluator.eval(it, context)) }
            val keys = orderKeys.map { key ->
                if (key.item != null) values[key.item] else Values.normalize(Evaluator.eval(key.expr!!, context))
            }
            results += Output(values, keys)
            return needed == null || results.size < needed
        }

        val bucket = schema?.let(::rowsOf)
        if (!aggregating) {
            if (schema == null) {
                if (Evaluator.matches(where, Evaluator.NO_ROW)) emit(Evaluator.NO_ROW)
            } else {
                scanRows(schema, bucket!!, Planner.plan(schema, where), where) { row, _ -> emit(EvalContext(row)) }
            }
        } else {
            val groups = LinkedHashMap<List<Any?>, Group>()
            val newGroup = { keyValues: List<Any?> -> Group(keyValues, aggregates.map { Accumulator(it.name, it.star) }) }
            val consume = { context: EvalContext ->
                val keyValues = groupBy.map { Values.normalize(Evaluator.eval(it, context)) }
                val group = groups.getOrPut(keyValues.map(Values::hashable)) { newGroup(keyValues) }
                aggregates.forEachIndexed { i, call ->
                    group.accumulators[i].add(if (call.star) null else Evaluator.eval(call.args[0], context))
                }
            }
            if (schema == null) {
                if (Evaluator.matches(where, Evaluator.NO_ROW)) consume(Evaluator.NO_ROW)
            } else {
                scanRows(schema, bucket!!, Planner.plan(schema, where), where) { row, _ ->
                    consume(EvalContext(row))
                    true
                }
            }
            if (groupBy.isEmpty() && groups.isEmpty()) groups[emptyList()] = newGroup(emptyList())
            for (group in groups.values) {
                val context = EvalContext(null, group.accumulators.map { it.result() }, group.keyValues)
                if (Evaluator.matches(havingExpr, context)) emit(context)
            }
        }

        var rows: List<Output> = results
        if (statement.distinct) {
            val seen = HashSet<List<Any?>>()
            rows = rows.filter { seen.add(it.values.map(Values::hashable)) }
        }
        if (orderKeys.isNotEmpty()) {
            rows = rows.sortedWith { a, b ->
                var result = 0
                for (i in orderKeys.indices) {
                    result = Values.compareForOrder(a.sortKeys[i], b.sortKeys[i])
                    if (orderKeys[i].descending) result = -result
                    if (result != 0) break
                }
                result
            }
        }
        if (offset > 0) rows = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (limit != null) rows = rows.take(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return SqlResult(names, rows.map { Row(names, it.values) })
    }

    private fun ordinal(value: Any?, count: Int, clause: String): Int {
        val n = value as Long
        if (n !in 1..count) throw SqlException("$clause position $n is out of range")
        return (n - 1).toInt()
    }

    private fun constant(binder: Binder, expression: Expr, clause: String): Long {
        val bound = binder.bind(expression)
        if (bound.anyNode { it is BoundColumn }) throw SqlException("$clause must be a constant")
        val value = Values.normalize(Evaluator.eval(bound, Evaluator.NO_ROW))
        if (value !is Long || value < 0) throw SqlException("$clause must be a non-negative integer")
        return value
    }

    /**
     * Rewrites an expression of a grouped query so that it only reads group values and aggregate
     * results: GROUP BY expressions become [GroupRef]s and aggregate calls [AggregateRef]s.
     */
    private fun rewriteForGroups(expression: Expr, groupBy: List<Expr>, aggregates: MutableList<Call>): Expr {
        val group = groupBy.indexOf(expression)
        if (group >= 0) return GroupRef(group)
        return when {
            expression is Call && expression.isAggregate() -> {
                if (expression.args.any { it.containsAggregate() }) throw SqlException("Aggregate functions cannot be nested")
                var index = aggregates.indexOf(expression)
                if (index < 0) {
                    aggregates += expression
                    index = aggregates.lastIndex
                }
                AggregateRef(index)
            }
            expression is BoundColumn ->
                throw SqlException("Column ${expression.name} must appear in the GROUP BY clause or be used in an aggregate function")
            else -> expression.mapChildren { rewriteForGroups(it, groupBy, aggregates) }
        }
    }

    // ---- EXPLAIN

    private fun explain(statement: Statement, params: List<Any?>): SqlResult {
        val (tableName, condition) = when (statement) {
            is Select -> (statement.table ?: return planResult("CONSTANT ROW")) to statement.where
            is Update -> statement.table to statement.where
            is Delete -> statement.table to statement.where
            else -> throw SqlException("EXPLAIN supports SELECT, UPDATE and DELETE only")
        }
        val schema = table(tableName)
        val where = bindCondition(Binder(schema, params), condition)
        return planResult(Planner.plan(schema, where).describe(schema))
    }

    private fun planResult(line: String): SqlResult = SqlResult(PLAN_COLUMNS, listOf(Row(PLAN_COLUMNS, listOf(line))))

    private fun normalizeParam(value: Any?): Any? = when (value) {
        null, is Long, is String, is ByteArray -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is Double -> value.also { if (it.isNaN()) throw SqlException("NaN cannot be used as a parameter") }
        is Float -> value.toDouble().also { if (it.isNaN()) throw SqlException("NaN cannot be used as a parameter") }
        else -> throw SqlException("Unsupported parameter type ${value::class.qualifiedName}")
    }

    private companion object {
        val EMPTY = SqlResult(emptyList(), emptyList())
        val PLAN_COLUMNS = listOf("plan")
    }
}
