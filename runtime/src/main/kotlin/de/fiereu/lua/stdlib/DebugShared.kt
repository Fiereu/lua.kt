package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaCoroutine
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaUserdata
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg
import de.fiereu.lua.luaTypeName
import de.fiereu.lua.runtime.CallFrame
import de.fiereu.lua.runtime.Cell
import de.fiereu.lua.runtime.LuaClosure
import de.fiereu.lua.runtime.Scope
import de.fiereu.lua.set

/*
 * Implementations of the introspection calls shared by the `debug` and `debugEx`
 * libraries. They read the interpreter's live call-frame stack added for debugging.
 */

/** Skips a leading coroutine argument (which this engine ignores), returning the real first index. */
private fun List<LuaValue>.skipThread(): Int = if (arg(0) is LuaCoroutine) 1 else 0

internal fun debugTraceback(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): LuaValue {
    val start = args.skipThread()
    val message = args.arg(start)
    if (message != LuaNil && message !is LuaString) return message
    val text = (message as? LuaString)?.bytes?.utf8()
    return LuaString.of(runtime.interpreter.buildTraceback(text))
}

internal fun debugGetInfo(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): LuaValue {
    val start = args.skipThread()
    val info = runtime.newTable()
    when (val selector = args.arg(start)) {
        is LuaInteger -> fillFromLevel(runtime, info, selector.value.toInt()) ?: return LuaNil
        is LuaFloat -> fillFromLevel(runtime, info, selector.value.toInt()) ?: return LuaNil
        is LuaFunction -> fillFromFunction(runtime, info, selector)
        else -> return LuaNil
    }
    return info
}

private fun fillFromLevel(
    runtime: LuaRuntime,
    info: LuaTable,
    level: Int,
): Unit? {
    val frame = runtime.interpreter.frameAtLevel(level) ?: return null
    fillFromClosure(runtime, info, frame.closure)
    info["currentline"] = LuaInteger(frame.currentLine.toLong())
    return Unit
}

private fun fillFromFunction(
    runtime: LuaRuntime,
    info: LuaTable,
    function: LuaFunction,
) {
    if (function is LuaClosure) {
        fillFromClosure(runtime, info, function)
    } else {
        info["source"] = LuaString.of("=[C]")
        info["short_src"] = LuaString.of("[C]")
        info["what"] = LuaString.of("C")
        info["linedefined"] = LuaInteger(-1)
        info["lastlinedefined"] = LuaInteger(-1)
        info["nparams"] = LuaInteger(0)
        info["nups"] = LuaInteger(0)
        info["isvararg"] = LuaBoolean.FALSE
    }
    info["currentline"] = LuaInteger(-1)
    info["func"] = function
}

private fun fillFromClosure(
    runtime: LuaRuntime,
    info: LuaTable,
    closure: LuaClosure,
) {
    val proto = closure.proto
    info["source"] = LuaString.of(closure.chunkName ?: "?")
    info["short_src"] = LuaString.of(runtime.interpreter.displaySource(closure.chunkName))
    info["what"] = LuaString.of(if (closure.isMain) "main" else "Lua")
    info["name"] = closure.name?.let { LuaString.of(it) } ?: LuaNil
    info["linedefined"] = LuaInteger(proto.position.line.toLong())
    info["lastlinedefined"] = LuaInteger(proto.position.line.toLong())
    info["nparams"] = LuaInteger(proto.parameters.size.toLong())
    info["nups"] = LuaInteger(upvaluesOf(closure).size.toLong())
    info["isvararg"] = LuaBoolean.of(proto.isVararg)
    info["func"] = closure
}

internal fun debugGetLocal(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): List<LuaValue> {
    val start = args.skipThread()
    val frame = runtime.interpreter.frameAtLevel(args.checkInteger(start, "getlocal").toInt()) ?: return listOf(LuaNil)
    val index = args.checkInteger(start + 1, "getlocal").toInt()
    val local = frame.orderedLocals().getOrNull(index - 1) ?: return listOf(LuaNil)
    return listOf(LuaString.of(local.first), local.second.value)
}

internal fun debugSetLocal(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): List<LuaValue> {
    val start = args.skipThread()
    val frame = runtime.interpreter.frameAtLevel(args.checkInteger(start, "setlocal").toInt()) ?: return listOf(LuaNil)
    val index = args.checkInteger(start + 1, "setlocal").toInt()
    val local = frame.orderedLocals().getOrNull(index - 1) ?: return listOf(LuaNil)
    if (!local.second.readOnly) local.second.value = args.arg(start + 2)
    return listOf(LuaString.of(local.first))
}

internal fun debugGetUpvalue(args: List<LuaValue>): List<LuaValue> {
    val closure = args.arg(0) as? LuaClosure ?: return listOf(LuaNil)
    val upvalue = upvaluesOf(closure).getOrNull(args.checkInteger(1, "getupvalue").toInt() - 1) ?: return listOf(LuaNil)
    return listOf(LuaString.of(upvalue.first), upvalue.second.value)
}

internal fun debugSetUpvalue(args: List<LuaValue>): List<LuaValue> {
    val closure = args.arg(0) as? LuaClosure ?: return listOf(LuaNil)
    val upvalue = upvaluesOf(closure).getOrNull(args.checkInteger(1, "setupvalue").toInt() - 1) ?: return listOf(LuaNil)
    if (!upvalue.second.readOnly) upvalue.second.value = args.arg(2)
    return listOf(LuaString.of(upvalue.first))
}

internal fun debugSetHook(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): List<LuaValue> {
    val start = args.skipThread()
    val controller = runtime.interpreter.debugController()
    when (val hook = args.arg(start)) {
        LuaNil -> {
            controller.setHook(null, "", 0)
        }

        is LuaFunction -> {
            val mask = (args.arg(start + 1) as? LuaString)?.bytes?.utf8() ?: ""
            val count = args.optInteger(start + 2, 0).toInt()
            controller.setHook(hook, mask, count)
        }

        else -> {
            badArgument(start, "sethook", "function or nil expected")
        }
    }
    return emptyList()
}

internal fun debugGetHook(runtime: LuaRuntime): List<LuaValue> {
    val controller = runtime.interpreter.debug ?: return listOf(LuaNil)
    val hook = controller.hook() ?: return listOf(LuaNil)
    return listOf(hook, LuaString.of(controller.hookMask()), LuaInteger(controller.hookCount().toLong()))
}

/** Captured cells of a closure, in proximity order (innermost capture first). */
private fun upvaluesOf(closure: LuaClosure): List<Pair<String, Cell>> {
    val map = LinkedHashMap<String, Cell>()
    var current: Scope? = closure.captured
    while (current != null) {
        for ((name, cell) in current.localCells()) map.putIfAbsent(name, cell)
        current = current.enclosing
    }
    return map.entries.map { it.key to it.value }
}

/** A name->value table of all locals visible in the frame at [level], for `locals(level)`. */
internal fun debugLocalsTable(
    runtime: LuaRuntime,
    level: Int,
): LuaValue {
    val frame: CallFrame = runtime.interpreter.frameAtLevel(level) ?: return LuaNil
    val table = runtime.newTable()
    for ((name, cell) in frame.visibleLocals()) table[name] = cell.value
    return table
}

internal fun debugSetMetatable(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): List<LuaValue> {
    val value = args.arg(0)
    val meta = args.arg(1)
    val table = if (meta == LuaNil) null else meta as? LuaTable ?: badArgument(1, "setmetatable", "nil or table expected")
    when (value) {
        is LuaTable -> value.metatable = table
        is LuaUserdata -> value.metatable = table
        else -> runtime.interpreter.setTypeMetatable(value.luaTypeName, table)
    }
    return listOf(value)
}

/** A name->value table of the upvalues captured by the function in `args[0]`. */
internal fun debugUpvaluesTable(
    runtime: LuaRuntime,
    args: List<LuaValue>,
): LuaValue {
    val closure = args.arg(0) as? LuaClosure ?: return runtime.newTable()
    val table = runtime.newTable()
    for ((name, cell) in upvaluesOf(closure)) table[name] = cell.value
    return table
}

internal fun debugProfileReport(runtime: LuaRuntime): LuaValue {
    val controller = runtime.interpreter.debug ?: return runtime.newTable()
    val table = runtime.newTable()
    controller.profileReport().forEachIndexed { index, (name, calls, nanos) ->
        val entry = runtime.newTable()
        entry["name"] = LuaString.of(name)
        entry["calls"] = LuaInteger(calls)
        entry["time"] = LuaFloat(nanos / NANOS_PER_SECOND)
        table[(index + 1).toLong()] = entry
    }
    return table
}

internal fun debugBreakpointsTable(runtime: LuaRuntime): LuaValue {
    val controller = runtime.interpreter.debug ?: return runtime.newTable()
    val table = runtime.newTable()
    controller.breakpointList().forEachIndexed { index, breakpoint ->
        val entry = runtime.newTable()
        entry["id"] = LuaInteger(breakpoint.id.toLong())
        entry["source"] = LuaString.of(breakpoint.source)
        entry["line"] = LuaInteger(breakpoint.line.toLong())
        entry["enabled"] = LuaBoolean.of(breakpoint.enabled)
        table[(index + 1).toLong()] = entry
    }
    return table
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
