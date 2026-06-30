package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg
import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.isTruthy
import de.fiereu.lua.luaTypeName
import java.io.ByteArrayOutputStream

/** The table library: insert, remove, concat, sort, pack, unpack, move. */
internal object TableLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "table") {
        function("insert") { insert(it) }
        function("remove") { remove(it) }
        result("concat") { LuaString(concat(it)) }
        function("unpack") { unpack(it) }
        result("pack") { pack(runtime, it) }
        function("sort") {
            sort(interpreter, it)
            emptyList()
        }
        function("move") { move(it) }
        result("create") { runtime.newTable() }
    }

    private fun insert(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "insert")
        val length = table.length
        when (args.size) {
            2 -> {
                table.rawSet(LuaInteger(length + 1), args[1])
            }

            3 -> {
                val position = args.checkInteger(1, "insert")
                if (position < 1 || position > length + 1) badArgument(1, "insert", "position out of bounds")
                var i = length
                while (i >= position) {
                    table.rawSet(LuaInteger(i + 1), table.rawGet(LuaInteger(i)))
                    i--
                }
                table.rawSet(LuaInteger(position), args[2])
            }

            else -> {
                throw LuaError.of("wrong number of arguments to 'insert'")
            }
        }
        return emptyList()
    }

    private fun remove(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "remove")
        val length = table.length
        val position = args.optInteger(1, length)
        if (length == 0L && args.size < 2) return listOf(LuaNil)
        val removed = table.rawGet(LuaInteger(position))
        var i = position
        while (i < length) {
            table.rawSet(LuaInteger(i), table.rawGet(LuaInteger(i + 1)))
            i++
        }
        table.rawSet(LuaInteger(length), LuaNil)
        return listOf(removed)
    }

    private fun concat(args: List<LuaValue>): LuaBytes {
        val table = args.checkTable(0, "concat")
        val separator = if (args.arg(1) == LuaNil) LuaBytes.EMPTY else args.checkBytes(1, "concat")
        val first = args.optInteger(2, 1)
        val last = args.optInteger(3, table.length)
        val out = ByteArrayOutputStream()
        var i = first
        while (i <= last) {
            out.write(elementBytes(table, i).toByteArray())
            if (i < last) out.write(separator.toByteArray())
            i++
        }
        return LuaBytes.wrap(out.toByteArray())
    }

    private fun elementBytes(
        table: LuaTable,
        index: Long,
    ): LuaBytes =
        when (val value = table.rawGet(LuaInteger(index))) {
            is LuaString -> {
                value.bytes
            }

            is LuaInteger -> {
                LuaBytes.of(
                    de.fiereu.lua.common.LuaNumbers
                        .integerToString(value.value),
                )
            }

            is LuaFloat -> {
                LuaBytes.of(
                    de.fiereu.lua.common.LuaNumbers
                        .floatToString(value.value),
                )
            }

            else -> {
                throw LuaError.of("invalid value (at index $index) in table for 'concat'")
            }
        }

    private fun unpack(args: List<LuaValue>): List<LuaValue> {
        val table = args.checkTable(0, "unpack")
        val first = args.optInteger(1, 1)
        val last = args.optInteger(2, table.length)
        val result = ArrayList<LuaValue>()
        var i = first
        while (i <= last) {
            result.add(table.rawGet(LuaInteger(i)))
            i++
        }
        return result
    }

    private fun pack(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): LuaTable {
        val table = runtime.newTable()
        args.forEachIndexed { index, value -> table.rawSet(LuaInteger((index + 1).toLong()), value) }
        table.rawSet(LuaString.of("n"), LuaInteger(args.size.toLong()))
        return table
    }

    private fun sort(
        interpreter: de.fiereu.lua.runtime.Interpreter,
        args: List<LuaValue>,
    ) {
        val table = args.checkTable(0, "sort")
        val comparator = args.arg(1)
        val length = table.length.toInt()
        val elements = ArrayList<LuaValue>(length)
        for (i in 1..length) elements.add(table.rawGet(LuaInteger(i.toLong())))
        elements.sortWith { a, b -> compare(interpreter, comparator, a, b) }
        for (i in 1..length) table.rawSet(LuaInteger(i.toLong()), elements[i - 1])
    }

    private fun compare(
        interpreter: de.fiereu.lua.runtime.Interpreter,
        comparator: LuaValue,
        a: LuaValue,
        b: LuaValue,
    ): Int {
        if (less(interpreter, comparator, a, b)) return -1
        if (less(interpreter, comparator, b, a)) return 1
        return 0
    }

    private fun less(
        interpreter: de.fiereu.lua.runtime.Interpreter,
        comparator: LuaValue,
        a: LuaValue,
        b: LuaValue,
    ): Boolean {
        if (comparator != LuaNil) {
            return interpreter.call(comparator, listOf(a, b)).firstOrNull()?.isTruthy ?: false
        }
        return defaultLess(a, b)
    }

    private fun defaultLess(
        a: LuaValue,
        b: LuaValue,
    ): Boolean {
        val left = numberOf(a)
        val right = numberOf(b)
        if (a is LuaString && b is LuaString) return a.bytes.utf8() < b.bytes.utf8()
        if (left != null && right != null && a !is LuaString && b !is LuaString) return left < right
        throw LuaError.of("attempt to compare ${a.luaTypeName} with ${b.luaTypeName}")
    }

    private fun move(args: List<LuaValue>): List<LuaValue> {
        val source = args.checkTable(0, "move")
        val from = args.checkInteger(1, "move")
        val end = args.checkInteger(2, "move")
        val to = args.checkInteger(3, "move")
        val destination = if (args.arg(4) == LuaNil) source else args.checkTable(4, "move")
        if (end >= from) {
            var i = 0L
            while (from + i <= end) {
                destination.rawSet(LuaInteger(to + i), source.rawGet(LuaInteger(from + i)))
                i++
            }
        }
        return listOf(destination)
    }
}
