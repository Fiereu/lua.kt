package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
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
import de.fiereu.lua.runtime.Interpreter
import java.io.ByteArrayOutputStream
import java.util.Locale

/** The string library, including the Lua pattern engine and `string.format`. */
internal object StringLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "string") {
        result("len") { LuaInteger(it.checkBytes(0, "len").size.toLong()) }
        result("sub") { LuaString(sub(it)) }
        result("upper") { LuaString(mapBytes(it.checkBytes(0, "upper")) { byte -> upperByte(byte) }) }
        result("lower") { LuaString(mapBytes(it.checkBytes(0, "lower")) { byte -> lowerByte(byte) }) }
        result("rep") { LuaString(rep(it)) }
        result("reverse") { LuaString(reverse(it.checkBytes(0, "reverse"))) }
        function("byte") { byte(it) }
        result("char") { LuaString(char(it)) }
        function("find") { find(it) }
        function("match") { match(it) }
        result("gmatch") { gmatch(it) }
        function("gsub") { gsub(interpreter, it) }
        result("format") { LuaString(format(interpreter, it)) }
        result("pack") { LuaString(StringPack.pack(it)) }
        function("unpack") { StringPack.unpack(it) }
        result("packsize") { LuaInteger(StringPack.packSize(it)) }
        function("dump") { throw LuaError.of("unable to dump given function") }
        exposeAsTypeMetatable("string")
    }

    private fun sub(args: List<LuaValue>): LuaBytes {
        val bytes = args.checkBytes(0, "sub")
        val length = bytes.size
        var start = posRelat(args.optInteger(1, 1), length)
        var end = posRelat(args.optInteger(2, -1), length)
        if (start < 1) start = 1
        if (end > length) end = length
        if (start > end) return LuaBytes.EMPTY
        return LuaBytes.of(bytes.toByteArray().copyOfRange(start - 1, end))
    }

    private fun rep(args: List<LuaValue>): LuaBytes {
        val bytes = args.checkBytes(0, "rep")
        val count = args.checkInteger(1, "rep")
        if (count <= 0) return LuaBytes.EMPTY
        val separator = if (args.arg(2) == LuaNil) LuaBytes.EMPTY else args.checkBytes(2, "rep")
        val out = ByteArrayOutputStream()
        for (i in 0 until count) {
            if (i > 0) out.write(separator.toByteArray())
            out.write(bytes.toByteArray())
        }
        return LuaBytes.wrap(out.toByteArray())
    }

    private fun reverse(bytes: LuaBytes): LuaBytes {
        val data = bytes.toByteArray()
        data.reverse()
        return LuaBytes.wrap(data)
    }

    private fun byte(args: List<LuaValue>): List<LuaValue> {
        val bytes = args.checkBytes(0, "byte")
        val length = bytes.size
        var start = posRelat(args.optInteger(1, 1), length)
        var end = posRelat(args.optInteger(2, start.toLong()), length)
        if (start < 1) start = 1
        if (end > length) end = length
        val result = ArrayList<LuaValue>()
        for (i in start..end) result.add(LuaInteger((bytes[i - 1].toInt() and 0xFF).toLong()))
        return result
    }

    private fun char(args: List<LuaValue>): LuaBytes {
        val data = ByteArray(args.size)
        args.forEachIndexed { index, value ->
            val code = integerOf(value) ?: badArgument(index, "char", "number expected")
            if (code < 0 || code > 255) badArgument(index, "char", "value out of range")
            data[index] = code.toByte()
        }
        return LuaBytes.wrap(data)
    }

    private fun find(args: List<LuaValue>): List<LuaValue> {
        val source = args.checkBytes(0, "find").toByteArray()
        val pattern = args.checkBytes(1, "find").toByteArray()
        val init = startIndex(args.optInteger(2, 1), source.size)
        if (init > source.size) return listOf(LuaNil)
        val plain = args.arg(3).isTruthy
        if (plain) {
            val at = indexOf(source, pattern, init)
            return if (at < 0) listOf(LuaNil) else listOf(LuaInteger((at + 1).toLong()), LuaInteger((at + pattern.size).toLong()))
        }
        val matched = LuaPattern(source, pattern).find(init) ?: return listOf(LuaNil)
        val head = listOf<LuaValue>(LuaInteger((matched.start + 1).toLong()), LuaInteger(matched.end.toLong()))
        return head + matched.captures.map { captureValue(it) }
    }

    private fun match(args: List<LuaValue>): List<LuaValue> {
        val source = args.checkBytes(0, "match").toByteArray()
        val pattern = args.checkBytes(1, "match").toByteArray()
        val init = startIndex(args.optInteger(2, 1), source.size)
        if (init > source.size) return listOf(LuaNil)
        val matched = LuaPattern(source, pattern).find(init) ?: return listOf(LuaNil)
        return matchValues(matched, source)
    }

    private fun gmatch(args: List<LuaValue>): LuaFunction {
        val source = args.checkBytes(0, "gmatch").toByteArray()
        val pattern = args.checkBytes(1, "gmatch").toByteArray()
        var position = 0
        return LuaFunction {
            while (position <= source.size) {
                val matched = LuaPattern(source, pattern).find(position) ?: return@LuaFunction listOf(LuaNil)
                position = if (matched.end > matched.start) matched.end else matched.end + 1
                return@LuaFunction matchValues(matched, source)
            }
            listOf(LuaNil)
        }
    }

    private fun gsub(
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val source = args.checkBytes(0, "gsub").toByteArray()
        val pattern = args.checkBytes(1, "gsub").toByteArray()
        val replacement = args.arg(2)
        val limit = if (args.arg(3) == LuaNil) Long.MAX_VALUE else args.checkInteger(3, "gsub")
        val out = ByteArrayOutputStream()
        var position = 0
        var count = 0L
        while (position <= source.size && count < limit) {
            val matched = LuaPattern(source, pattern).find(position) ?: break
            count++
            val whole = source.copyOfRange(matched.start, matched.end)
            out.write(source, position, matched.start - position)
            out.write(replace(interpreter, replacement, matched, source, whole))
            position =
                if (matched.end > matched.start) {
                    matched.end
                } else {
                    if (matched.end < source.size) out.write(source[matched.end].toInt())
                    matched.end + 1
                }
        }
        if (position < source.size) out.write(source, position, source.size - position)
        return listOf(LuaString(LuaBytes.wrap(out.toByteArray())), LuaInteger(count))
    }

    private fun replace(
        interpreter: Interpreter,
        replacement: LuaValue,
        matched: PatternMatch,
        source: ByteArray,
        whole: ByteArray,
    ): ByteArray {
        val produced =
            when (replacement) {
                is LuaString -> return substituteString(replacement.bytes.toByteArray(), matched, whole)
                is LuaTable -> replacement[firstCapture(matched, whole)]
                is LuaFunction -> interpreter.call(replacement, matchValues(matched, source)).firstOrNull() ?: LuaNil
                else -> throw LuaError.of("bad argument #3 to 'gsub' (string/function/table expected)")
            }
        return when (produced) {
            LuaNil, de.fiereu.lua.LuaBoolean.FALSE -> {
                whole
            }

            is LuaString -> {
                produced.bytes.toByteArray()
            }

            is LuaInteger -> {
                LuaBytes
                    .of(
                        de.fiereu.lua.common.LuaNumbers
                            .integerToString(produced.value),
                    ).toByteArray()
            }

            else -> {
                throw LuaError.of("invalid replacement value (a ${produced.luaTypeName})")
            }
        }
    }

    private fun substituteString(
        template: ByteArray,
        matched: PatternMatch,
        whole: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < template.size) {
            val c = template[i].toInt() and 0xFF
            if (c != '%'.code) {
                out.write(c)
                i++
                continue
            }
            i++
            val next = template[i].toInt() and 0xFF
            when {
                next == '%'.code -> out.write('%'.code)
                next == '0'.code -> out.write(whole)
                next in '1'.code..'9'.code -> out.write(captureBytes(matched, whole, next - '0'.code))
                else -> throw LuaError.of("invalid use of '%' in replacement string")
            }
            i++
        }
        return out.toByteArray()
    }

    private fun captureBytes(
        matched: PatternMatch,
        whole: ByteArray,
        index: Int,
    ): ByteArray {
        if (matched.captures.isEmpty() && index == 1) return whole
        val capture = matched.captures.getOrNull(index - 1) ?: throw LuaError.of("invalid capture index %$index in replacement string")
        return when (capture) {
            is StringCapture -> capture.bytes
            is PositionCapture -> LuaBytes.of(capture.index.toString()).toByteArray()
        }
    }

    private fun firstCapture(
        matched: PatternMatch,
        whole: ByteArray,
    ): LuaValue =
        if (matched.captures.isEmpty()) {
            LuaString(LuaBytes.wrap(whole))
        } else {
            captureValue(matched.captures.first())
        }

    private fun matchValues(
        matched: PatternMatch,
        source: ByteArray,
    ): List<LuaValue> {
        if (matched.captures.isEmpty()) {
            return listOf(LuaString(LuaBytes.of(source.copyOfRange(matched.start, matched.end))))
        }
        return matched.captures.map { captureValue(it) }
    }

    private fun captureValue(capture: Capture): LuaValue =
        when (capture) {
            is StringCapture -> LuaString(LuaBytes.wrap(capture.bytes))
            is PositionCapture -> LuaInteger(capture.index.toLong())
        }

    private fun format(
        interpreter: Interpreter,
        args: List<LuaValue>,
    ): LuaBytes {
        val template = args.checkBytes(0, "format").toByteArray()
        val out = ByteArrayOutputStream()
        var i = 0
        var argIndex = 1
        while (i < template.size) {
            val c = template[i].toInt() and 0xFF
            if (c != '%'.code) {
                out.write(c)
                i++
                continue
            }
            val specEnd = parseSpecEnd(template, i + 1)
            val spec = String(template, i, specEnd - i + 1, Charsets.US_ASCII)
            val conversion = template[specEnd].toInt().toChar()
            if (conversion == '%') {
                out.write('%'.code)
            } else {
                out.write(formatOne(interpreter, spec, conversion, args, argIndex).toByteArray(Charsets.ISO_8859_1))
                argIndex++
            }
            i = specEnd + 1
        }
        return LuaBytes.wrap(out.toByteArray())
    }

    private fun parseSpecEnd(
        template: ByteArray,
        from: Int,
    ): Int {
        var i = from
        while (i < template.size &&
            (template[i].toInt().toChar() in "-+ #0" || template[i].toInt().toChar().isDigit() || template[i].toInt().toChar() == '.')
        ) {
            i++
        }
        if (i >= template.size) throw LuaError.of("invalid conversion to 'format'")
        return i
    }

    private fun formatOne(
        interpreter: Interpreter,
        spec: String,
        conversion: Char,
        args: List<LuaValue>,
        argIndex: Int,
    ): String =
        when (conversion) {
            'd', 'i', 'u' -> {
                String.format(Locale.ROOT, spec.dropLast(1) + "d", args.checkInteger(argIndex, "format"))
            }

            'o', 'x', 'X' -> {
                String.format(Locale.ROOT, spec, args.checkInteger(argIndex, "format"))
            }

            'c' -> {
                args
                    .checkInteger(argIndex, "format")
                    .toInt()
                    .toChar()
                    .toString()
            }

            'f', 'F', 'e', 'E', 'g', 'G', 'a', 'A' -> {
                String.format(Locale.ROOT, spec, args.checkNumber(argIndex, "format"))
            }

            's' -> {
                String.format(Locale.ROOT, spec, interpreter.toDisplayString(args.arg(argIndex)).utf8())
            }

            'q' -> {
                quote(args.checkBytes(argIndex, "format"))
            }

            else -> {
                throw LuaError.of("invalid conversion '%$conversion' to 'format'")
            }
        }

    private fun quote(bytes: LuaBytes): String {
        val out = StringBuilder("\"")
        for (i in 0 until bytes.size) {
            when (val c = bytes[i].toInt() and 0xFF) {
                '"'.code -> out.append("\\\"")
                '\\'.code -> out.append("\\\\")
                '\n'.code -> out.append("\\n")
                '\r'.code -> out.append("\\r")
                0 -> out.append("\\0")
                else -> out.append(c.toChar())
            }
        }
        out.append("\"")
        return out.toString()
    }

    private fun mapBytes(
        bytes: LuaBytes,
        transform: (Int) -> Int,
    ): LuaBytes {
        val data = bytes.toByteArray()
        for (i in data.indices) data[i] = transform(data[i].toInt() and 0xFF).toByte()
        return LuaBytes.wrap(data)
    }

    private fun upperByte(c: Int): Int = if (c in 'a'.code..'z'.code) c - 32 else c

    private fun lowerByte(c: Int): Int = if (c in 'A'.code..'Z'.code) c + 32 else c

    private fun posRelat(
        pos: Long,
        length: Int,
    ): Int {
        if (pos >= 0) return pos.toInt()
        if (-pos > length) return 0
        return length + pos.toInt() + 1
    }

    private fun startIndex(
        init: Long,
        length: Int,
    ): Int {
        val relative = posRelat(init, length)
        return if (relative < 1) 0 else relative - 1
    }

    private fun indexOf(
        source: ByteArray,
        pattern: ByteArray,
        from: Int,
    ): Int {
        if (pattern.isEmpty()) return from
        var i = from
        while (i + pattern.size <= source.size) {
            var matched = true
            for (j in pattern.indices) {
                if (source[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }
}
