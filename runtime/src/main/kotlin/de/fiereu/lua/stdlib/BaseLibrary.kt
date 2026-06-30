package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.SecurityAction
import de.fiereu.lua.arg
import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral
import de.fiereu.lua.isTruthy
import de.fiereu.lua.luaTypeName
import de.fiereu.lua.runtime.Interpreter
import de.fiereu.lua.runtime.LuaRuntimeImpl
import java.io.ByteArrayOutputStream
import java.io.File

/** The base library: `print`, `type`, `pcall`, `pairs`, `setmetatable`, and friends. */
internal object BaseLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.populate(target) {
        val impl = runtime as LuaRuntimeImpl
        val nextFunction = LuaFunction { args -> nextEntry(args) }
        val warnEnabled = booleanArrayOf(false)

        constant("_G", target)
        constant("_VERSION", LuaString.of("Lua 5.5"))
        function("print") { print(impl, interpreter, it) }
        result("type") { LuaString.of(it.arg(0).luaTypeName) }
        result("tostring") { LuaString(interpreter.toDisplayString(it.arg(0))) }
        function("tonumber") { toNumber(it) }
        function("assert") { assert(it) }
        function("error") { raiseError(it) }
        function("pcall") { protectedCall(interpreter, it) }
        function("xpcall") { protectedCallWithHandler(interpreter, it) }
        result("rawget") { it.checkTable(0, "rawget").rawGet(it.arg(1)) }
        function("rawset") { rawSet(it) }
        result("rawequal") { LuaBoolean.of(interpreter.rawEquals(it.arg(0), it.arg(1))) }
        result("rawlen") { LuaInteger(rawLength(it)) }
        function("select") { select(it) }
        constant("next", nextFunction)
        function("pairs") { pairs(interpreter, nextFunction, it) }
        function("ipairs") { ipairs(it) }
        function("setmetatable") { setMetatable(it) }
        function("getmetatable") { getMetatable(interpreter, it) }
        function("load") { load(impl, it) }
        function("collectgarbage") { collectGarbage(it) }
        function("loadfile") { loadFile(impl, it) }
        function("dofile") { doFile(impl, it) }
        function("warn") { warn(warnEnabled, it) }
    }

    private fun loadFile(
        impl: LuaRuntimeImpl,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val name =
            (args.arg(0) as? LuaString)?.bytes?.utf8()
                ?: return listOf(LuaNil, LuaString.of("loadfile from stdin is not supported"))
        impl.guard(SecurityAction.READ_FILE, name)
        impl.guard(SecurityAction.LOAD_CODE, name)
        return try {
            listOf(impl.load(File(name).readBytes(), "@$name"))
        } catch (error: LuaError) {
            listOf(LuaNil, error.value)
        } catch (error: java.io.IOException) {
            listOf(LuaNil, LuaString.of("cannot open '$name': ${error.message}"))
        }
    }

    private fun doFile(
        impl: LuaRuntimeImpl,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val loaded = loadFile(impl, args)
        val function = loaded[0]
        if (function !is LuaFunction) throw LuaError(loaded.getOrElse(1) { LuaString.of("cannot load file") })
        return impl.call(function)
    }

    private fun warn(
        enabled: BooleanArray,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val control = (args.arg(0) as? LuaString)?.bytes?.utf8()
        if (args.size == 1 && control != null && control.startsWith("@")) {
            if (control == "@on") enabled[0] = true
            if (control == "@off") enabled[0] = false
            return emptyList()
        }
        if (enabled[0]) {
            val message = buildString { for (i in args.indices) append(args.checkBytes(i, "warn").utf8()) }
            System.err.println("Lua warning: $message")
        }
        return emptyList()
    }

    private fun load(
        impl: LuaRuntimeImpl,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val name = (args.arg(1) as? LuaString)?.bytes?.utf8() ?: "=(load)"
        impl.guard(SecurityAction.LOAD_CODE, name)
        val source = loadSource(args.arg(0)) ?: return listOf(LuaNil, LuaString.of("bad argument to 'load'"))
        return try {
            listOf(impl.load(source, name))
        } catch (error: LuaError) {
            listOf(LuaNil, error.value)
        }
    }

    private fun loadSource(chunk: LuaValue): ByteArray? =
        when (chunk) {
            is LuaString -> chunk.bytes.toByteArray()
            is LuaFunction -> collectChunk(chunk)
            else -> null
        }

    private fun collectChunk(producer: LuaFunction): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val piece = producer.call(emptyList()).firstOrNull() ?: LuaNil
            if (piece !is LuaString || piece.bytes.size == 0) break
            out.write(piece.bytes.toByteArray())
        }
        return out.toByteArray()
    }

    private fun collectGarbage(args: List<LuaValue>): List<LuaValue> {
        val option = (args.arg(0) as? LuaString)?.bytes?.utf8() ?: "collect"
        return if (option == "count") listOf(LuaFloat(0.0), LuaInteger(0)) else listOf(LuaInteger(0))
    }

    private fun print(
        impl: LuaRuntimeImpl,
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val out = ByteArrayOutputStream()
        args.forEachIndexed { index, value ->
            if (index > 0) out.write('\t'.code)
            out.write(interpreter.toDisplayString(value).toByteArray())
        }
        out.write('\n'.code)
        impl.config.standardOutput(LuaBytes.wrap(out.toByteArray()))
        return emptyList()
    }

    private fun toNumber(args: List<LuaValue>): List<LuaValue> {
        val base = args.arg(1)
        if (base == LuaNil) return listOf(numeralValue(args.arg(0)))
        val radix = integerOf(base)?.toInt() ?: badArgument(1, "tonumber", "number expected")
        val text = (args.arg(0) as? LuaString)?.bytes?.utf8()?.trim() ?: badArgument(0, "tonumber", "string expected")
        val parsed = text.toLongOrNull(radix) ?: return listOf(LuaNil)
        return listOf(LuaInteger(parsed))
    }

    private fun numeralValue(value: LuaValue): LuaValue =
        when (value) {
            is LuaInteger, is LuaFloat -> {
                value
            }

            is LuaString -> {
                when (val parsed = LuaNumbers.parse(value.bytes.utf8())) {
                    is LuaNumeral.Int -> LuaInteger(parsed.value)
                    is LuaNumeral.Float -> LuaFloat(parsed.value)
                    null -> LuaNil
                }
            }

            else -> {
                LuaNil
            }
        }

    private fun assert(args: List<LuaValue>): List<LuaValue> {
        if (args.arg(0).isTruthy) return args
        val message = if (args.size > 1) args[1] else LuaString.of("assertion failed!")
        throw LuaError(message)
    }

    private fun raiseError(args: List<LuaValue>): List<LuaValue> = throw LuaError(args.arg(0))

    private fun protectedCall(
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): List<LuaValue> =
        try {
            listOf(LuaBoolean.TRUE) + interpreter.call(args.arg(0), args.drop(1))
        } catch (error: LuaError) {
            listOf(LuaBoolean.FALSE, error.value)
        } catch (error: Exception) {
            listOf(LuaBoolean.FALSE, LuaString.of(error.message ?: "error"))
        }

    private fun protectedCallWithHandler(
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val function = args.arg(0)
        val handler = args.arg(1)
        return try {
            listOf(LuaBoolean.TRUE) + interpreter.call(function, args.drop(2))
        } catch (error: LuaError) {
            listOf(LuaBoolean.FALSE) + interpreter.call(handler, listOf(error.value))
        }
    }

    private fun rawSet(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "rawset")
        table.rawSet(args.arg(1), args.arg(2))
        return listOf(table)
    }

    private fun rawLength(args: List<LuaValue>): Long =
        when (val value = args.arg(0)) {
            is LuaTable -> value.length
            is LuaString -> value.bytes.size.toLong()
            else -> badArgument(0, "rawlen", "table or string expected")
        }

    private fun select(args: List<LuaValue>): List<LuaValue> {
        val first = args.arg(0)
        if (first is LuaString && first.bytes.utf8() == "#") {
            return listOf(LuaInteger((args.size - 1).toLong()))
        }
        val n = integerOf(first) ?: badArgument(0, "select", "number expected")
        val rest = args.drop(1)
        if (n < 0) {
            val start = rest.size + n.toInt()
            if (start < 0) badArgument(0, "select", "index out of range")
            return rest.drop(start)
        }
        if (n == 0L) badArgument(0, "select", "index out of range")
        return rest.drop((n - 1).toInt())
    }

    private fun nextEntry(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "next")
        val key = args.arg(1)
        val pairs = table.entries().toList()
        if (key == LuaNil) {
            return if (pairs.isEmpty()) listOf(LuaNil) else listOf(pairs[0].first, pairs[0].second)
        }
        val index = pairs.indexOfFirst { it.first == key }
        if (index < 0 || index == pairs.size - 1) return listOf(LuaNil)
        return listOf(pairs[index + 1].first, pairs[index + 1].second)
    }

    private fun pairs(
        interpreter: Interpreter,
        nextFunction: LuaFunction,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val value = args.arg(0)
        val handler = interpreter.metamethod(value, PAIRS)
        if (handler != LuaNil) return interpreter.call(handler, listOf(value))
        val table = args.checkTable(0, "pairs")
        return listOf(nextFunction, table, LuaNil)
    }

    private fun ipairs(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "ipairs")
        val iterator =
            LuaFunction { inner ->
                val index = (inner.arg(1) as LuaInteger).value + 1
                val value = table[LuaInteger(index)]
                if (value == LuaNil) listOf(LuaNil) else listOf(LuaInteger(index), value)
            }
        return listOf(iterator, table, LuaInteger(0))
    }

    private fun setMetatable(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "setmetatable")
        when (val meta = args.arg(1)) {
            LuaNil -> table.metatable = null
            is LuaTable -> table.metatable = meta
            else -> badArgument(1, "setmetatable", "nil or table expected")
        }
        return listOf(table)
    }

    private fun getMetatable(
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val metatable = interpreter.metatableOf(args.arg(0)) ?: return listOf(LuaNil)
        val protected = metatable.rawGet(METATABLE)
        return listOf(if (protected != LuaNil) protected else metatable)
    }

    private val PAIRS = LuaString.of("__pairs")
    private val METATABLE = LuaString.of("__metatable")
}
