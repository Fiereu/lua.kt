package de.fiereu.lua.runtime

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaCoroutine
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaUserdata
import de.fiereu.lua.LuaValue
import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral
import de.fiereu.lua.common.LuaSyntaxException
import de.fiereu.lua.common.SourcePosition
import de.fiereu.lua.isTruthy
import de.fiereu.lua.luaTypeName
import de.fiereu.lua.parser.LuaParser
import de.fiereu.lua.parser.ast.AssignStat
import de.fiereu.lua.parser.ast.Attribute
import de.fiereu.lua.parser.ast.BinaryExpr
import de.fiereu.lua.parser.ast.BinaryOp
import de.fiereu.lua.parser.ast.Block
import de.fiereu.lua.parser.ast.BoolLiteral
import de.fiereu.lua.parser.ast.BreakStat
import de.fiereu.lua.parser.ast.CallExpr
import de.fiereu.lua.parser.ast.CallStat
import de.fiereu.lua.parser.ast.Chunk
import de.fiereu.lua.parser.ast.DoBlock
import de.fiereu.lua.parser.ast.Expr
import de.fiereu.lua.parser.ast.FloatLiteral
import de.fiereu.lua.parser.ast.FunctionExpr
import de.fiereu.lua.parser.ast.GenericFor
import de.fiereu.lua.parser.ast.GlobalDeclaration
import de.fiereu.lua.parser.ast.GlobalWildcard
import de.fiereu.lua.parser.ast.GotoStat
import de.fiereu.lua.parser.ast.IfStat
import de.fiereu.lua.parser.ast.IndexExpr
import de.fiereu.lua.parser.ast.IntLiteral
import de.fiereu.lua.parser.ast.KeyedField
import de.fiereu.lua.parser.ast.LabelStat
import de.fiereu.lua.parser.ast.LocalDeclaration
import de.fiereu.lua.parser.ast.LocalFunctionDeclaration
import de.fiereu.lua.parser.ast.MethodCallExpr
import de.fiereu.lua.parser.ast.NameRef
import de.fiereu.lua.parser.ast.NamedField
import de.fiereu.lua.parser.ast.NilLiteral
import de.fiereu.lua.parser.ast.NumericFor
import de.fiereu.lua.parser.ast.ParenExpr
import de.fiereu.lua.parser.ast.PositionalField
import de.fiereu.lua.parser.ast.RepeatLoop
import de.fiereu.lua.parser.ast.Stat
import de.fiereu.lua.parser.ast.StringLiteral
import de.fiereu.lua.parser.ast.TableExpr
import de.fiereu.lua.parser.ast.UnaryExpr
import de.fiereu.lua.parser.ast.UnaryOp
import de.fiereu.lua.parser.ast.VarargExpr
import de.fiereu.lua.parser.ast.WhileLoop
import java.util.concurrent.atomic.AtomicInteger

/** A Lua closure: a function prototype plus the scope it captured at definition. */
internal class LuaClosure(
    val proto: FunctionExpr,
    val captured: Scope,
    val chunkName: String?,
    private val interpreter: Interpreter,
    var name: String? = null,
    val isMain: Boolean = false,
) : LuaFunction {
    override fun call(arguments: List<LuaValue>): List<LuaValue> = interpreter.callClosure(this, arguments)
}

/** The tree-walking evaluator behind the runtime's engine boundary. */
internal class Interpreter(
    val globals: LuaTable,
    private val tableFactory: () -> LuaTable,
    private val maxCallDepth: Int,
) {
    private val typeMetatables = HashMap<String, LuaTable>()

    private val frames = ThreadLocal.withInitial { ArrayDeque<CallFrame>() }

    private val executionControl = ThreadLocal<ManagedExecution?>()
    private val activeControls = AtomicInteger(0)

    /** Binds [execution] as the controller for the current thread (pause/stop checkpoints). */
    fun installControl(execution: ManagedExecution) {
        executionControl.set(execution)
        activeControls.incrementAndGet()
    }

    /** Unbinds the current thread's execution controller. */
    fun removeControl() {
        executionControl.remove()
        activeControls.decrementAndGet()
    }

    /**
     * The active debug controller, or null when nothing is attached. Statement
     * execution does a single null check against this, so a program with no
     * debugger or hook pays almost nothing.
     */
    var debug: DebugController? = null
        private set

    /** Lazily creates the debug controller on first use (attach, sethook, breakpoint, profiler). */
    fun debugController(): DebugController {
        val existing = debug
        if (existing != null) return existing
        val created = DebugController(this)
        debug = created
        return created
    }

    /** A bottom-first snapshot of the active Lua call frames (index 0 is the outermost). */
    fun callStack(): List<CallFrame> = frames.get().toList()

    /** The frame at a Lua stack level: 1 is the innermost running Lua function. */
    fun frameAtLevel(level: Int): CallFrame? {
        val stack = frames.get()
        val index = stack.size - level
        return stack.getOrNull(index)
    }

    /** Number of active Lua frames on the current thread. */
    fun currentDepth(): Int = frames.get().size

    /** The cleaned `short_src` form of a chunk name, as `getinfo` reports it. */
    fun displaySource(chunkName: String?): String = shortSource(chunkName)

    /** The shared metatable for a primitive type such as "string" (see `debug.setmetatable`). */
    fun typeMetatableFor(typeName: String): LuaTable? = typeMetatables[typeName]

    fun setTypeMetatable(
        typeName: String,
        table: LuaTable?,
    ) {
        if (table == null) typeMetatables.remove(typeName) else typeMetatables[typeName] = table
    }

    fun newTable(): LuaTable = tableFactory()

    fun loadChunk(
        chunk: Chunk,
        chunkName: String?,
    ): LuaFunction {
        val proto = FunctionExpr(emptyList(), isVararg = true, varargName = null, body = chunk.body, position = ORIGIN)
        return LuaClosure(proto, Scope(null), chunkName, this, isMain = true)
    }

    fun callClosure(
        closure: LuaClosure,
        arguments: List<LuaValue>,
    ): List<LuaValue> {
        val stack = frames.get()
        if (stack.size >= maxCallDepth) throw LuaError(LuaString.of("stack overflow"), buildTraceback())
        val scope = Scope(closure.captured)
        val proto = closure.proto
        proto.parameters.forEachIndexed { index, name ->
            scope.define(name, arguments.getOrElse(index) { LuaNil })
        }
        val varargs =
            if (proto.isVararg && arguments.size > proto.parameters.size) {
                arguments.subList(proto.parameters.size, arguments.size).toList()
            } else {
                emptyList()
            }
        bindVarargName(proto, varargs, scope)
        val frame = CallFrame(closure, scope)
        val control = if (activeControls.get() > 0) executionControl.get() else null
        stack.addLast(frame)
        debug?.onCall(frame)
        try {
            return when (val result = Activation(varargs, closure.chunkName, frame, control).execBlock(proto.body, scope)) {
                is Exec.Return -> result.values
                else -> emptyList()
            }
        } catch (error: LuaError) {
            if (error.traceback != null) throw error
            debug?.onError(error, frame)
            throw LuaError(error.value, buildTraceback(), error.level, error)
        } finally {
            debug?.onReturn(frame)
            stack.removeLast()
        }
    }

    /** Renders a `stack traceback:` string from the active Lua frames, innermost first. */
    fun buildTraceback(message: String? = null): String {
        val builder = StringBuilder()
        if (message != null) builder.append(message).append('\n')
        builder.append("stack traceback:")
        val stack = frames.get()
        for (i in stack.indices.reversed()) {
            val frame = stack[i]
            builder
                .append("\n\t")
                .append(shortSource(frame.chunkName))
                .append(':')
                .append(frame.currentLine)
                .append(": in ")
                .append(frameDescription(frame))
        }
        return builder.toString()
    }

    private fun frameDescription(frame: CallFrame): String =
        when {
            frame.closure.isMain -> "main chunk"
            frame.functionName != null -> "function '${frame.functionName}'"
            else -> "function <${shortSource(frame.chunkName)}:${frame.closure.proto.position.line}>"
        }

    /** Compiles [code] and evaluates it with [scope] as its enclosing scope (watch expressions). */
    fun evalInScope(
        code: String,
        scope: Scope,
    ): List<LuaValue> {
        val chunk =
            try {
                LuaParser.parse("return $code", EVAL_CHUNK)
            } catch (ignored: LuaSyntaxException) {
                LuaParser.parse(code, EVAL_CHUNK)
            }
        val proto = FunctionExpr(emptyList(), isVararg = true, varargName = null, body = chunk.body, position = ORIGIN)
        return callClosure(LuaClosure(proto, scope, EVAL_CHUNK, this), emptyList())
    }

    private fun bindVarargName(
        proto: FunctionExpr,
        varargs: List<LuaValue>,
        scope: Scope,
    ) {
        val name = proto.varargName ?: return
        val table = newTable()
        varargs.forEachIndexed { index, value -> table.rawSet(LuaInteger((index + 1).toLong()), value) }
        table.rawSet(LuaString.of("n"), LuaInteger(varargs.size.toLong()))
        scope.define(name, table, readOnly = true)
    }

    fun callValue(
        callee: LuaValue,
        arguments: List<LuaValue>,
        position: SourcePosition,
    ): List<LuaValue> {
        var target = callee
        var args = arguments
        var hops = 0
        while (target !is LuaFunction) {
            val handler = metamethod(target, Metamethods.CALL)
            if (handler == LuaNil) {
                throw runtimeError("attempt to call a ${target.luaTypeName} value", position, null)
            }
            if (++hops > MAX_CALL_CHAIN) throw runtimeError("'__call' chain too long; possible loop", position, null)
            args = listOf(target) + args
            target = handler
        }
        return target.call(args)
    }

    fun metatableOf(value: LuaValue): LuaTable? =
        when (value) {
            is LuaTable -> value.metatable
            is LuaUserdata -> value.metatable
            else -> typeMetatables[value.luaTypeName]
        }

    fun metamethod(
        value: LuaValue,
        name: LuaString,
    ): LuaValue = metatableOf(value)?.rawGet(name) ?: LuaNil

    /** Call a value from library code, where no source position is available. */
    fun call(
        callee: LuaValue,
        arguments: List<LuaValue>,
    ): List<LuaValue> = callValue(callee, arguments, ORIGIN)

    /** Raw equality (no `__eq`), exposed for the base library's `rawequal`. */
    fun rawEquals(
        left: LuaValue,
        right: LuaValue,
    ): Boolean = rawEqual(left, right)

    /** Render a value as `tostring` would, honoring the `__tostring` metamethod. */
    fun toDisplayString(value: LuaValue): LuaBytes {
        if (value is LuaString) return value.bytes
        val handler = metamethod(value, Metamethods.TOSTRING)
        if (handler != LuaNil) {
            val result = callValue(handler, listOf(value), ORIGIN).firstOrNull() ?: LuaNil
            if (result is LuaString) return result.bytes
            throw LuaError.of("'__tostring' must return a string")
        }
        return LuaBytes.of(defaultDisplay(value))
    }

    private fun runtimeError(
        message: String,
        position: SourcePosition,
        chunkName: String?,
    ): LuaError = LuaError(LuaString.of("${shortSource(chunkName)}:${position.line}: $message"))

    sealed interface Exec {
        data object Normal : Exec

        data class Return(
            val values: List<LuaValue>,
        ) : Exec

        data object Break : Exec

        data class Goto(
            val label: String,
        ) : Exec
    }

    /** One function call's activation: it owns the varargs and the active chunk name. */
    inner class Activation(
        private val varargs: List<LuaValue>,
        private val chunkName: String?,
        private val frame: CallFrame,
        private val control: ManagedExecution?,
    ) {
        fun execBlock(
            block: Block,
            scope: Scope,
        ): Exec {
            val statements = block.statements
            var index = 0
            while (index < statements.size) {
                when (val result = execStatement(statements[index], scope)) {
                    Exec.Normal -> {
                        index++
                    }

                    is Exec.Goto -> {
                        val target = labelIndex(statements, result.label)
                        if (target < 0) return result
                        index = target
                    }

                    else -> {
                        return result
                    }
                }
            }
            val ret = block.returnStatement ?: return Exec.Normal
            return Exec.Return(evalList(ret.values, scope))
        }

        private fun execStatement(
            statement: Stat,
            scope: Scope,
        ): Exec {
            frame.currentLine = statement.position.line
            frame.currentScope = scope
            control?.checkpoint()
            debug?.onStatement(frame)
            when (statement) {
                is LocalDeclaration -> execLocal(statement, scope)
                is LocalFunctionDeclaration -> execLocalFunction(statement, scope)
                is GlobalDeclaration -> execGlobal(statement, scope)
                is GlobalWildcard -> Unit
                is AssignStat -> execAssign(statement, scope)
                is CallStat -> evalMulti(statement.call, scope)
                is DoBlock -> return execBlock(statement.body, Scope(scope))
                is WhileLoop -> return execWhile(statement, scope)
                is RepeatLoop -> return execRepeat(statement, scope)
                is IfStat -> return execIf(statement, scope)
                is NumericFor -> return execNumericFor(statement, scope)
                is GenericFor -> return execGenericFor(statement, scope)
                is BreakStat -> return Exec.Break
                is GotoStat -> return Exec.Goto(statement.label)
                is LabelStat -> Unit
                else -> Unit
            }
            return Exec.Normal
        }

        private fun execLocal(
            statement: LocalDeclaration,
            scope: Scope,
        ) {
            val values = evalList(statement.values, scope)
            statement.names.forEachIndexed { index, attribName ->
                scope.define(
                    attribName.name,
                    values.getOrElse(index) { LuaNil },
                    readOnly = attribName.attribute != null,
                )
            }
        }

        private fun execLocalFunction(
            statement: LocalFunctionDeclaration,
            scope: Scope,
        ) {
            val cell = scope.define(statement.name, LuaNil)
            cell.value = LuaClosure(statement.body, scope, chunkName, this@Interpreter, statement.name)
        }

        private fun execGlobal(
            statement: GlobalDeclaration,
            scope: Scope,
        ) {
            if (statement.values.isEmpty()) return
            val values = evalList(statement.values, scope)
            statement.names.forEachIndexed { index, attribName ->
                val key = LuaString.of(attribName.name)
                if (globals.rawGet(key) != LuaNil) {
                    throw runtimeError("global '${attribName.name}' already defined", statement.position, chunkName)
                }
                globals[key] = values.getOrElse(index) { LuaNil }
            }
        }

        private fun execAssign(
            statement: AssignStat,
            scope: Scope,
        ) {
            val assigners = statement.targets.map { prepareTarget(it, scope) }
            val values = evalList(statement.values, scope)
            assigners.forEachIndexed { index, assign -> assign(values.getOrElse(index) { LuaNil }) }
        }

        private fun prepareTarget(
            target: Expr,
            scope: Scope,
        ): (LuaValue) -> Unit =
            when (target) {
                is NameRef -> {
                    prepareNameTarget(target, scope)
                }

                is IndexExpr -> {
                    val receiver = evalExpr(target.receiver, scope)
                    val key = evalExpr(target.key, scope)
                    val assigner: (LuaValue) -> Unit = { value -> setIndex(receiver, key, value, target.position) }
                    assigner
                }

                else -> {
                    throw runtimeError("cannot assign to this expression", target.position, chunkName)
                }
            }

        private fun prepareNameTarget(
            target: NameRef,
            scope: Scope,
        ): (LuaValue) -> Unit {
            val cell = scope.resolve(target.name)
            if (cell != null) {
                return { value ->
                    if (cell.readOnly) {
                        throw runtimeError("attempt to assign to const variable '${target.name}'", target.position, chunkName)
                    }
                    cell.value = value
                }
            }
            return { value -> globals[LuaString.of(target.name)] = value }
        }

        private fun execWhile(
            statement: WhileLoop,
            scope: Scope,
        ): Exec {
            while (evalExpr(statement.condition, scope).isTruthy) {
                control?.checkpoint()
                when (val result = execBlock(statement.body, Scope(scope))) {
                    Exec.Normal -> Unit
                    Exec.Break -> return Exec.Normal
                    else -> return result
                }
            }
            return Exec.Normal
        }

        private fun execRepeat(
            statement: RepeatLoop,
            scope: Scope,
        ): Exec {
            while (true) {
                control?.checkpoint()
                val inner = Scope(scope)
                when (val result = execBlock(statement.body, inner)) {
                    Exec.Normal -> Unit
                    Exec.Break -> return Exec.Normal
                    else -> return result
                }
                if (evalExpr(statement.condition, inner).isTruthy) return Exec.Normal
            }
        }

        private fun execIf(
            statement: IfStat,
            scope: Scope,
        ): Exec {
            for (clause in statement.clauses) {
                if (evalExpr(clause.condition, scope).isTruthy) {
                    return execBlock(clause.body, Scope(scope))
                }
            }
            val elseBody = statement.elseBody ?: return Exec.Normal
            return execBlock(elseBody, Scope(scope))
        }

        private fun execNumericFor(
            statement: NumericFor,
            scope: Scope,
        ): Exec {
            val start = forNumber(evalExpr(statement.start, scope), "initial", statement.position)
            val limit = forNumber(evalExpr(statement.limit, scope), "limit", statement.position)
            val stepExpr = statement.step
            val step =
                if (stepExpr != null) {
                    forNumber(evalExpr(stepExpr, scope), "step", statement.position)
                } else {
                    LuaInteger(1)
                }
            return if (start is LuaInteger && limit is LuaInteger && step is LuaInteger) {
                integerFor(statement, start.value, limit.value, step.value, scope)
            } else {
                floatFor(statement, toDouble(start), toDouble(limit), toDouble(step), scope)
            }
        }

        private fun integerFor(
            statement: NumericFor,
            start: Long,
            limit: Long,
            step: Long,
            scope: Scope,
        ): Exec {
            if (step == 0L) throw runtimeError("'for' step is zero", statement.position, chunkName)
            var current = start
            while (if (step > 0) current <= limit else current >= limit) {
                control?.checkpoint()
                val iteration = Scope(scope)
                iteration.define(statement.name, LuaInteger(current), readOnly = true)
                when (val result = execBlock(statement.body, iteration)) {
                    Exec.Normal -> Unit
                    Exec.Break -> return Exec.Normal
                    else -> return result
                }
                val next = current + step
                if (step > 0 && next < current) break
                if (step < 0 && next > current) break
                current = next
            }
            return Exec.Normal
        }

        private fun floatFor(
            statement: NumericFor,
            start: Double,
            limit: Double,
            step: Double,
            scope: Scope,
        ): Exec {
            if (step == 0.0) throw runtimeError("'for' step is zero", statement.position, chunkName)
            var current = start
            while (if (step > 0) current <= limit else current >= limit) {
                control?.checkpoint()
                val iteration = Scope(scope)
                iteration.define(statement.name, LuaFloat(current), readOnly = true)
                when (val result = execBlock(statement.body, iteration)) {
                    Exec.Normal -> Unit
                    Exec.Break -> return Exec.Normal
                    else -> return result
                }
                current += step
            }
            return Exec.Normal
        }

        private fun execGenericFor(
            statement: GenericFor,
            scope: Scope,
        ): Exec {
            val initial = evalList(statement.expressions, scope)
            val iterator = initial.getOrElse(0) { LuaNil }
            val state = initial.getOrElse(1) { LuaNil }
            var control = initial.getOrElse(2) { LuaNil }
            while (true) {
                this.control?.checkpoint()
                val results = callValue(iterator, listOf(state, control), statement.position)
                val first = results.firstOrNull() ?: LuaNil
                if (first == LuaNil) return Exec.Normal
                control = first
                val iteration = Scope(scope)
                statement.names.forEachIndexed { index, name ->
                    iteration.define(name, results.getOrElse(index) { LuaNil })
                }
                when (val result = execBlock(statement.body, iteration)) {
                    Exec.Normal -> Unit
                    Exec.Break -> return Exec.Normal
                    else -> return result
                }
            }
        }

        private fun forNumber(
            value: LuaValue,
            role: String,
            position: SourcePosition,
        ): LuaValue =
            when (value) {
                is LuaInteger, is LuaFloat -> value
                else -> throw runtimeError("'for' $role value must be a number", position, chunkName)
            }

        fun evalList(
            expressions: List<Expr>,
            scope: Scope,
        ): List<LuaValue> {
            if (expressions.isEmpty()) return emptyList()
            val values = ArrayList<LuaValue>(expressions.size)
            for (i in 0 until expressions.size - 1) values.add(evalExpr(expressions[i], scope))
            values.addAll(evalMulti(expressions.last(), scope))
            return values
        }

        private fun evalMulti(
            expression: Expr,
            scope: Scope,
        ): List<LuaValue> =
            when (expression) {
                is CallExpr -> evalCall(expression, scope)
                is MethodCallExpr -> evalMethodCall(expression, scope)
                is VarargExpr -> varargs
                else -> listOf(evalExpr(expression, scope))
            }

        private fun evalExpr(
            expression: Expr,
            scope: Scope,
        ): LuaValue =
            when (expression) {
                is NilLiteral -> LuaNil
                is BoolLiteral -> LuaBoolean.of(expression.value)
                is IntLiteral -> LuaInteger(expression.value)
                is FloatLiteral -> LuaFloat(expression.value)
                is StringLiteral -> LuaString(expression.value)
                is VarargExpr -> varargs.firstOrNull() ?: LuaNil
                is NameRef -> readName(expression, scope)
                is IndexExpr -> indexGet(evalExpr(expression.receiver, scope), evalExpr(expression.key, scope), expression.position)
                is CallExpr -> evalCall(expression, scope).firstOrNull() ?: LuaNil
                is MethodCallExpr -> evalMethodCall(expression, scope).firstOrNull() ?: LuaNil
                is FunctionExpr -> LuaClosure(expression, scope, chunkName, this@Interpreter)
                is TableExpr -> buildTable(expression, scope)
                is BinaryExpr -> evalBinary(expression, scope)
                is UnaryExpr -> evalUnary(expression, scope)
                is ParenExpr -> evalExpr(expression.inner, scope)
            }

        private fun readName(
            reference: NameRef,
            scope: Scope,
        ): LuaValue {
            val cell = scope.resolve(reference.name)
            if (cell != null) return cell.value
            if (reference.name == "_ENV") return globals
            return globals[LuaString.of(reference.name)]
        }

        private fun evalCall(
            expression: CallExpr,
            scope: Scope,
        ): List<LuaValue> {
            val callee = evalExpr(expression.callee, scope)
            val arguments = evalList(expression.arguments, scope)
            return callValue(callee, arguments, expression.position)
        }

        private fun evalMethodCall(
            expression: MethodCallExpr,
            scope: Scope,
        ): List<LuaValue> {
            val receiver = evalExpr(expression.receiver, scope)
            val method = indexGet(receiver, LuaString.of(expression.method), expression.position)
            val arguments = ArrayList<LuaValue>()
            arguments.add(receiver)
            arguments.addAll(evalList(expression.arguments, scope))
            return callValue(method, arguments, expression.position)
        }

        private fun buildTable(
            expression: TableExpr,
            scope: Scope,
        ): LuaValue {
            val table = newTable()
            var arrayIndex = 1L
            expression.fields.forEachIndexed { index, field ->
                when (field) {
                    is NamedField -> {
                        table.rawSet(LuaString.of(field.key), evalExpr(field.value, scope))
                    }

                    is KeyedField -> {
                        table.rawSet(evalExpr(field.key, scope), evalExpr(field.value, scope))
                    }

                    is PositionalField -> {
                        if (index == expression.fields.lastIndex) {
                            for (value in evalMulti(field.value, scope)) {
                                table.rawSet(LuaInteger(arrayIndex++), value)
                            }
                        } else {
                            table.rawSet(LuaInteger(arrayIndex++), evalExpr(field.value, scope))
                        }
                    }
                }
            }
            return table
        }

        private fun evalBinary(
            expression: BinaryExpr,
            scope: Scope,
        ): LuaValue =
            when (expression.operator) {
                BinaryOp.AND -> {
                    evalExpr(expression.left, scope).let { if (!it.isTruthy) it else evalExpr(expression.right, scope) }
                }

                BinaryOp.OR -> {
                    evalExpr(expression.left, scope).let { if (it.isTruthy) it else evalExpr(expression.right, scope) }
                }

                else -> {
                    applyBinary(
                        expression.operator,
                        evalExpr(expression.left, scope),
                        evalExpr(expression.right, scope),
                        expression.position,
                    )
                }
            }

        private fun applyBinary(
            operator: BinaryOp,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue =
            when (operator) {
                BinaryOp.CONCAT -> concat(left, right, position)
                BinaryOp.EQ -> LuaBoolean.of(valuesEqual(left, right, position))
                BinaryOp.NE -> LuaBoolean.of(!valuesEqual(left, right, position))
                BinaryOp.LT -> LuaBoolean.of(lessThan(left, right, position))
                BinaryOp.LE -> LuaBoolean.of(lessOrEqual(left, right, position))
                BinaryOp.GT -> LuaBoolean.of(lessThan(right, left, position))
                BinaryOp.GE -> LuaBoolean.of(lessOrEqual(right, left, position))
                else -> arith(operator, left, right, position)
            }

        private fun evalUnary(
            expression: UnaryExpr,
            scope: Scope,
        ): LuaValue {
            val operand = evalExpr(expression.operand, scope)
            return when (expression.operator) {
                UnaryOp.NEG -> negate(operand, expression.position)
                UnaryOp.NOT -> LuaBoolean.of(!operand.isTruthy)
                UnaryOp.LEN -> length(operand, expression.position)
                UnaryOp.BNOT -> bitwiseNot(operand, expression.position)
            }
        }

        private fun indexGet(
            receiver: LuaValue,
            key: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (receiver is LuaTable) return receiver[key]
            return when (val handler = metamethod(receiver, Metamethods.INDEX)) {
                LuaNil -> throw runtimeError("attempt to index a ${receiver.luaTypeName} value", position, chunkName)
                is LuaFunction -> handler.call(listOf(receiver, key)).firstOrNull() ?: LuaNil
                is LuaTable -> handler[key]
                else -> indexGet(handler, key, position)
            }
        }

        private fun setIndex(
            receiver: LuaValue,
            key: LuaValue,
            value: LuaValue,
            position: SourcePosition,
        ) {
            if (receiver is LuaTable) {
                receiver[key] = value
                return
            }
            when (val handler = metamethod(receiver, Metamethods.NEWINDEX)) {
                LuaNil -> throw runtimeError("attempt to index a ${receiver.luaTypeName} value", position, chunkName)
                is LuaFunction -> handler.call(listOf(receiver, key, value))
                is LuaTable -> handler[key] = value
                else -> setIndex(handler, key, value, position)
            }
        }

        private fun arith(
            operator: BinaryOp,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (operator in BITWISE_OPS) return bitwise(operator, left, right, position)
            val leftNumber = numericValue(left)
            val rightNumber = numericValue(right)
            if (leftNumber != null && rightNumber != null) {
                return numberArith(operator, leftNumber, rightNumber, position)
            }
            val handler = firstMetamethod(left, right, arithMetamethod(operator))
            if (handler != LuaNil) return callValue(handler, listOf(left, right), position).firstOrNull() ?: LuaNil
            val bad = if (leftNumber == null) left else right
            throw runtimeError("attempt to perform arithmetic on a ${bad.luaTypeName} value", position, chunkName)
        }

        private fun bitwise(
            operator: BinaryOp,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            val leftNumber = numericValue(left)
            val rightNumber = numericValue(right)
            if (leftNumber != null && rightNumber != null) {
                val a = toIntegerOrThrow(leftNumber, position)
                val b = toIntegerOrThrow(rightNumber, position)
                return LuaInteger(bitwiseLongs(operator, a, b))
            }
            val handler = firstMetamethod(left, right, arithMetamethod(operator))
            if (handler != LuaNil) return callValue(handler, listOf(left, right), position).firstOrNull() ?: LuaNil
            val bad = if (leftNumber == null) left else right
            throw runtimeError("attempt to perform bitwise operation on a ${bad.luaTypeName} value", position, chunkName)
        }

        private fun numberArith(
            operator: BinaryOp,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            val bothInteger = left is LuaInteger && right is LuaInteger
            return when (operator) {
                BinaryOp.ADD -> if (bothInteger) LuaInteger(asLong(left) + asLong(right)) else LuaFloat(toDouble(left) + toDouble(right))
                BinaryOp.SUB -> if (bothInteger) LuaInteger(asLong(left) - asLong(right)) else LuaFloat(toDouble(left) - toDouble(right))
                BinaryOp.MUL -> if (bothInteger) LuaInteger(asLong(left) * asLong(right)) else LuaFloat(toDouble(left) * toDouble(right))
                BinaryOp.DIV -> LuaFloat(toDouble(left) / toDouble(right))
                BinaryOp.POW -> LuaFloat(Math.pow(toDouble(left), toDouble(right)))
                BinaryOp.IDIV -> integerOrFloatDiv(bothInteger, left, right, position)
                BinaryOp.MOD -> integerOrFloatMod(bothInteger, left, right, position)
                else -> throw runtimeError("unsupported operator", position, chunkName)
            }
        }

        private fun integerOrFloatDiv(
            bothInteger: Boolean,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (bothInteger) {
                val divisor = asLong(right)
                if (divisor == 0L) throw runtimeError("attempt to perform 'n//0'", position, chunkName)
                return LuaInteger(Math.floorDiv(asLong(left), divisor))
            }
            return LuaFloat(Math.floor(toDouble(left) / toDouble(right)))
        }

        private fun integerOrFloatMod(
            bothInteger: Boolean,
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (bothInteger) {
                val divisor = asLong(right)
                if (divisor == 0L) throw runtimeError("attempt to perform 'n%%0'", position, chunkName)
                return LuaInteger(Math.floorMod(asLong(left), divisor))
            }
            return LuaFloat(floatMod(toDouble(left), toDouble(right)))
        }

        private fun negate(
            value: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            when (val number = numericValue(value)) {
                is LuaInteger -> return LuaInteger(-number.value)
                is LuaFloat -> return LuaFloat(-number.value)
                else -> Unit
            }
            val handler = metamethod(value, Metamethods.UNM)
            if (handler != LuaNil) return callValue(handler, listOf(value, value), position).firstOrNull() ?: LuaNil
            throw runtimeError("attempt to perform arithmetic on a ${value.luaTypeName} value", position, chunkName)
        }

        private fun bitwiseNot(
            value: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            val number = numericValue(value)
            if (number != null) return LuaInteger(toIntegerOrThrow(number, position).inv())
            val handler = metamethod(value, Metamethods.BNOT)
            if (handler != LuaNil) return callValue(handler, listOf(value, value), position).firstOrNull() ?: LuaNil
            throw runtimeError("attempt to perform bitwise operation on a ${value.luaTypeName} value", position, chunkName)
        }

        private fun length(
            value: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (value is LuaString) return LuaInteger(value.bytes.size.toLong())
            val handler = metamethod(value, Metamethods.LEN)
            if (handler != LuaNil) return callValue(handler, listOf(value), position).firstOrNull() ?: LuaNil
            if (value is LuaTable) return LuaInteger(value.length)
            throw runtimeError("attempt to get length of a ${value.luaTypeName} value", position, chunkName)
        }

        private fun concat(
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaValue {
            if (isStringable(left) && isStringable(right)) {
                return LuaString(toBytes(left) + toBytes(right))
            }
            val handler = firstMetamethod(left, right, Metamethods.CONCAT)
            if (handler != LuaNil) return callValue(handler, listOf(left, right), position).firstOrNull() ?: LuaNil
            val bad = if (!isStringable(left)) left else right
            throw runtimeError("attempt to concatenate a ${bad.luaTypeName} value", position, chunkName)
        }

        private fun valuesEqual(
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): Boolean {
            if (rawEqual(left, right)) return true
            val bothTables = left is LuaTable && right is LuaTable
            val bothUserdata = left is LuaUserdata && right is LuaUserdata
            if (!bothTables && !bothUserdata) return false
            val handler = firstMetamethod(left, right, Metamethods.EQ)
            if (handler == LuaNil) return false
            return callValue(handler, listOf(left, right), position).firstOrNull()?.isTruthy ?: false
        }

        private fun lessThan(
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): Boolean {
            if (isNumber(left) && isNumber(right)) return numberLess(left, right)
            if (left is LuaString && right is LuaString) return compareBytes(left.bytes, right.bytes) < 0
            val handler = firstMetamethod(left, right, Metamethods.LT)
            if (handler != LuaNil) return callValue(handler, listOf(left, right), position).firstOrNull()?.isTruthy ?: false
            throw compareError(left, right, position)
        }

        private fun lessOrEqual(
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): Boolean {
            if (isNumber(left) && isNumber(right)) return numberLessOrEqual(left, right)
            if (left is LuaString && right is LuaString) return compareBytes(left.bytes, right.bytes) <= 0
            val handler = firstMetamethod(left, right, Metamethods.LE)
            if (handler != LuaNil) return callValue(handler, listOf(left, right), position).firstOrNull()?.isTruthy ?: false
            throw compareError(left, right, position)
        }

        private fun compareError(
            left: LuaValue,
            right: LuaValue,
            position: SourcePosition,
        ): LuaError {
            val leftType = left.luaTypeName
            val rightType = right.luaTypeName
            val message =
                if (leftType == rightType) {
                    "attempt to compare two $leftType values"
                } else {
                    "attempt to compare $leftType with $rightType"
                }
            return runtimeError(message, position, chunkName)
        }

        private fun toIntegerOrThrow(
            number: LuaValue,
            position: SourcePosition,
        ): Long =
            when (number) {
                is LuaInteger -> {
                    number.value
                }

                is LuaFloat -> {
                    val asLong = number.value.toLong()
                    if (!number.value.isInfinite() && asLong.toDouble() == number.value) {
                        asLong
                    } else {
                        throw runtimeError("number has no integer representation", position, chunkName)
                    }
                }

                else -> {
                    throw runtimeError("number has no integer representation", position, chunkName)
                }
            }
    }

    private companion object {
        const val MAX_CALL_CHAIN = 15
        const val EVAL_CHUNK = "=(eval)"
        val ORIGIN = SourcePosition(0, 0)
        val BITWISE_OPS = setOf(BinaryOp.BAND, BinaryOp.BOR, BinaryOp.BXOR, BinaryOp.SHL, BinaryOp.SHR)

        fun labelIndex(
            statements: List<Stat>,
            label: String,
        ): Int = statements.indexOfFirst { it is LabelStat && it.name == label }

        fun defaultDisplay(value: LuaValue): String =
            when (value) {
                is LuaNil -> "nil"
                is LuaBoolean -> if (value.value) "true" else "false"
                is LuaInteger -> LuaNumbers.integerToString(value.value)
                is LuaFloat -> LuaNumbers.floatToString(value.value)
                is LuaString -> value.bytes.utf8()
                is LuaTable -> address("table", value)
                is LuaFunction -> address("function", value)
                is LuaUserdata -> address("userdata", value)
                is LuaCoroutine -> address("thread", value)
            }

        private fun address(
            kind: String,
            value: LuaValue,
        ): String = "$kind: 0x%08x".format(System.identityHashCode(value))

        fun shortSource(chunkName: String?): String =
            when {
                chunkName == null -> "?"
                chunkName.startsWith("=") || chunkName.startsWith("@") -> chunkName.substring(1)
                else -> "[string \"${chunkName.take(MAX_SOURCE)}\"]"
            }

        const val MAX_SOURCE = 60

        fun isNumber(value: LuaValue): Boolean = value is LuaInteger || value is LuaFloat

        fun isStringable(value: LuaValue): Boolean = value is LuaString || isNumber(value)

        fun asLong(value: LuaValue): Long = (value as LuaInteger).value

        fun toDouble(value: LuaValue): Double =
            when (value) {
                is LuaInteger -> value.value.toDouble()
                is LuaFloat -> value.value
                else -> error("not a number")
            }

        fun numericValue(value: LuaValue): LuaValue? =
            when (value) {
                is LuaInteger -> {
                    value
                }

                is LuaFloat -> {
                    value
                }

                is LuaString -> {
                    when (val parsed = LuaNumbers.parse(value.bytes.utf8())) {
                        null -> null
                        is LuaNumeral.Int -> LuaInteger(parsed.value)
                        is LuaNumeral.Float -> LuaFloat(parsed.value)
                    }
                }

                else -> {
                    null
                }
            }

        fun toBytes(value: LuaValue): LuaBytes =
            when (value) {
                is LuaString -> value.bytes
                is LuaInteger -> LuaBytes.of(LuaNumbers.integerToString(value.value))
                is LuaFloat -> LuaBytes.of(LuaNumbers.floatToString(value.value))
                else -> error("not stringable")
            }

        fun arithMetamethod(operator: BinaryOp): LuaString =
            when (operator) {
                BinaryOp.ADD -> Metamethods.ADD
                BinaryOp.SUB -> Metamethods.SUB
                BinaryOp.MUL -> Metamethods.MUL
                BinaryOp.DIV -> Metamethods.DIV
                BinaryOp.MOD -> Metamethods.MOD
                BinaryOp.POW -> Metamethods.POW
                BinaryOp.IDIV -> Metamethods.IDIV
                BinaryOp.BAND -> Metamethods.BAND
                BinaryOp.BOR -> Metamethods.BOR
                BinaryOp.BXOR -> Metamethods.BXOR
                BinaryOp.SHL -> Metamethods.SHL
                BinaryOp.SHR -> Metamethods.SHR
                else -> error("not an arithmetic operator")
            }

        fun bitwiseLongs(
            operator: BinaryOp,
            a: Long,
            b: Long,
        ): Long =
            when (operator) {
                BinaryOp.BAND -> a and b
                BinaryOp.BOR -> a or b
                BinaryOp.BXOR -> a xor b
                BinaryOp.SHL -> shift(a, b)
                BinaryOp.SHR -> shift(a, -b)
                else -> error("not a bitwise operator")
            }

        fun shift(
            value: Long,
            amount: Long,
        ): Long {
            if (amount <= -java.lang.Long.SIZE || amount >= java.lang.Long.SIZE) return 0
            return if (amount >= 0) value shl amount.toInt() else value ushr (-amount).toInt()
        }

        fun floatMod(
            a: Double,
            b: Double,
        ): Double {
            val result = a % b
            return if (result != 0.0 && (result < 0) != (b < 0)) result + b else result
        }

        fun numberLess(
            left: LuaValue,
            right: LuaValue,
        ): Boolean {
            if (left is LuaInteger && right is LuaInteger) return left.value < right.value
            return toDouble(left) < toDouble(right)
        }

        fun numberLessOrEqual(
            left: LuaValue,
            right: LuaValue,
        ): Boolean {
            if (left is LuaInteger && right is LuaInteger) return left.value <= right.value
            return toDouble(left) <= toDouble(right)
        }

        fun rawEqual(
            left: LuaValue,
            right: LuaValue,
        ): Boolean =
            when {
                left is LuaInteger && right is LuaInteger -> left.value == right.value
                left is LuaFloat && right is LuaFloat -> left.value == right.value
                left is LuaInteger && right is LuaFloat -> integerEqualsFloat(left.value, right.value)
                left is LuaFloat && right is LuaInteger -> integerEqualsFloat(right.value, left.value)
                left is LuaString && right is LuaString -> left == right
                else -> left === right
            }

        fun integerEqualsFloat(
            integer: Long,
            float: Double,
        ): Boolean = !float.isInfinite() && Math.floor(float) == float && float.toLong() == integer && integer.toDouble() == float

        fun compareBytes(
            left: LuaBytes,
            right: LuaBytes,
        ): Int {
            val limit = minOf(left.size, right.size)
            for (i in 0 until limit) {
                val a = left[i].toInt() and 0xFF
                val b = right[i].toInt() and 0xFF
                if (a != b) return a - b
            }
            return left.size - right.size
        }

        fun firstMetamethodIn(
            primary: LuaValue,
            secondary: LuaValue,
            name: LuaString,
            lookup: (LuaValue, LuaString) -> LuaValue,
        ): LuaValue {
            val first = lookup(primary, name)
            if (first != LuaNil) return first
            return lookup(secondary, name)
        }
    }

    private fun firstMetamethod(
        left: LuaValue,
        right: LuaValue,
        name: LuaString,
    ): LuaValue = firstMetamethodIn(left, right, name, ::metamethod)
}
