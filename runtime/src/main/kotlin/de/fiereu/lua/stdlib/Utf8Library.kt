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
import java.io.ByteArrayOutputStream

/** The UTF-8 library (manual §6.5). */
internal object Utf8Library : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "utf8") {
        constant("charpattern", LuaString(LuaBytes.wrap(CHAR_PATTERN)))
        result("char") { LuaString(char(it)) }
        function("codepoint") { codepoint(it) }
        function("len") { len(it) }
        function("offset") { offset(it) }
        function("codes") { codes(it) }
    }

    private fun char(args: List<LuaValue>): LuaBytes {
        val out = ByteArrayOutputStream()
        args.forEachIndexed { index, value ->
            val code = integerOf(value) ?: badArgument(index, "char", "number expected")
            encode(code, out)
        }
        return LuaBytes.wrap(out.toByteArray())
    }

    private fun codepoint(args: List<LuaValue>): List<LuaValue> {
        val bytes = args.checkBytes(0, "codepoint").toByteArray()
        val start = posRelat(args.optInteger(1, 1), bytes.size)
        val end = posRelat(args.optInteger(2, start.toLong()), bytes.size)
        val result = ArrayList<LuaValue>()
        var position = start - 1
        while (position < end) {
            val decoded = decode(bytes, position, lax = false) ?: throw LuaError.of("invalid UTF-8 code")
            result.add(LuaInteger(decoded.code))
            position = decoded.next
        }
        return result
    }

    private fun len(args: List<LuaValue>): List<LuaValue> {
        val bytes = args.checkBytes(0, "len").toByteArray()
        val start = posRelat(args.optInteger(1, 1), bytes.size)
        val end = posRelat(args.optInteger(2, -1), bytes.size)
        var position = start - 1
        var count = 0L
        while (position < end) {
            val decoded = decode(bytes, position, lax = false) ?: return listOf(LuaNil, LuaInteger((position + 1).toLong()))
            position = decoded.next
            count++
        }
        return listOf(LuaInteger(count))
    }

    private fun offset(args: List<LuaValue>): List<LuaValue> {
        val bytes = args.checkBytes(0, "offset").toByteArray()
        val n = args.checkInteger(1, "offset")
        val defaultStart = if (n >= 0) 1L else (bytes.size + 1).toLong()
        var position = posRelat(args.optInteger(2, defaultStart), bytes.size) - 1
        val result =
            when {
                n == 0L -> moveToStart(bytes, position)
                else -> moveBy(bytes, position, n)
            }
        return listOf(result?.let { LuaInteger((it + 1).toLong()) } ?: LuaNil)
    }

    private fun moveToStart(
        bytes: ByteArray,
        from: Int,
    ): Int {
        var position = from
        while (position > 0 && isContinuation(bytes[position])) position--
        return position
    }

    private fun moveBy(
        bytes: ByteArray,
        from: Int,
        n: Long,
    ): Int? = if (n > 0) moveForward(bytes, from, n) else moveBackward(bytes, from, n)

    private fun moveForward(
        bytes: ByteArray,
        from: Int,
        n: Long,
    ): Int? {
        var position = from
        var remaining = n - 1
        while (remaining > 0 && position < bytes.size) {
            position++
            while (position < bytes.size && isContinuation(bytes[position])) position++
            remaining--
        }
        return if (remaining == 0L) position else null
    }

    private fun moveBackward(
        bytes: ByteArray,
        from: Int,
        n: Long,
    ): Int? {
        var position = from
        var remaining = n
        while (remaining < 0 && position > 0) {
            position--
            while (position > 0 && isContinuation(bytes[position])) position--
            remaining++
        }
        return if (remaining == 0L) position else null
    }

    private fun codes(args: List<LuaValue>): List<LuaValue> {
        val value = args.arg(0)
        val bytes = args.checkBytes(0, "codes").toByteArray()
        val iterator =
            LuaFunction { inner ->
                val previous = (inner.arg(1) as LuaInteger).value.toInt()
                val position = if (previous == 0) 0 else skipToNext(bytes, previous - 1)
                if (position >= bytes.size) {
                    listOf(LuaNil)
                } else {
                    val decoded = decode(bytes, position, lax = false) ?: throw LuaError.of("invalid UTF-8 code")
                    listOf(LuaInteger((position + 1).toLong()), LuaInteger(decoded.code))
                }
            }
        return listOf(iterator, value, LuaInteger(0))
    }

    private fun skipToNext(
        bytes: ByteArray,
        from: Int,
    ): Int {
        var position = from + 1
        while (position < bytes.size && isContinuation(bytes[position])) position++
        return position
    }

    private class Decoded(
        val code: Long,
        val next: Int,
    )

    private fun decode(
        bytes: ByteArray,
        index: Int,
        lax: Boolean,
    ): Decoded? {
        if (index >= bytes.size) return null
        val first = bytes[index].toInt() and 0xFF
        if (first < 0x80) return Decoded(first.toLong(), index + 1)
        val sequence = sequenceLength(first, lax) ?: return null
        var value = (first and (0x7F shr sequence)).toLong()
        for (k in 1..sequence) {
            val continuation = bytes.getOrNull(index + k)?.toInt()?.and(0xFF) ?: return null
            if (continuation and 0xC0 != 0x80) return null
            value = (value shl 6) or (continuation and 0x3F).toLong()
        }
        if (!lax && (value > 0x10FFFF || value in 0xD800..0xDFFF)) return null
        return Decoded(value, index + sequence + 1)
    }

    private fun sequenceLength(
        first: Int,
        lax: Boolean,
    ): Int? =
        when (first) {
            in 0xC0..0xDF -> 1
            in 0xE0..0xEF -> 2
            in 0xF0..0xF7 -> 3
            in 0xF8..0xFB -> if (lax) 4 else null
            in 0xFC..0xFD -> if (lax) 5 else null
            else -> null
        }

    private fun encode(
        code: Long,
        out: ByteArrayOutputStream,
    ) {
        when {
            code < 0x80 -> {
                out.write(code.toInt())
            }

            code < 0x800 -> {
                out.write(0xC0 or (code shr 6).toInt())
                out.write(0x80 or (code and 0x3F).toInt())
            }

            code < 0x10000 -> {
                out.write(0xE0 or (code shr 12).toInt())
                out.write(0x80 or ((code shr 6) and 0x3F).toInt())
                out.write(0x80 or (code and 0x3F).toInt())
            }

            else -> {
                out.write(0xF0 or (code shr 18).toInt())
                out.write(0x80 or ((code shr 12) and 0x3F).toInt())
                out.write(0x80 or ((code shr 6) and 0x3F).toInt())
                out.write(0x80 or (code and 0x3F).toInt())
            }
        }
    }

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

    private fun posRelat(
        pos: Long,
        length: Int,
    ): Int {
        if (pos >= 0) return pos.toInt()
        if (-pos > length) return 0
        return length + pos.toInt() + 1
    }

    // The pattern "[\0-\x7F\xC2-\xFD][\x80-\xBF]*" matching one UTF-8 byte sequence.
    private val CHAR_PATTERN =
        intArrayOf(0x5B, 0x00, 0x2D, 0x7F, 0xC2, 0x2D, 0xFD, 0x5D, 0x5B, 0x80, 0x2D, 0xBF, 0x5D, 0x2A)
            .map { it.toByte() }
            .toByteArray()
}
