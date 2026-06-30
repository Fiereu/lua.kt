package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg
import de.fiereu.lua.common.LuaBytes
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/** `string.pack`/`unpack`/`packsize` (manual §6.5), the binary serialization codec. */
internal object StringPack {
    fun pack(args: List<LuaValue>): LuaBytes {
        val format = args.checkBytes(0, "pack").utf8()
        val out = ByteArrayOutputStream()
        val state = State()
        var argIndex = 1
        var i = 0
        while (i < format.length) {
            val option = format[i]
            i++
            val size = readSize(format, i).also { i += it.consumed }.value
            when (option) {
                in CONTROL -> {
                    applyControl(option, state)
                }

                'b', 'B', 'h', 'H', 'i', 'I', 'l', 'L', 'j', 'J', 'T' -> {
                    val width = integerWidth(option, size)
                    packInteger(out, args.checkInteger(argIndex++, "pack"), width, state.order)
                }

                'f' -> {
                    packFloat(out, args.checkNumber(argIndex++, "pack").toFloat(), state.order)
                }

                'd', 'n' -> {
                    packDouble(out, args.checkNumber(argIndex++, "pack"), state.order)
                }

                's' -> {
                    packString(out, args.checkBytes(argIndex++, "pack").toByteArray(), if (size > 0) size else 8, state.order)
                }

                'z' -> {
                    out.write(args.checkBytes(argIndex++, "pack").toByteArray())
                    out.write(0)
                }

                'x' -> {
                    out.write(0)
                }

                'X' -> {
                    if (i < format.length) {
                        i++
                        i += readSize(format, i).consumed
                    }
                }

                ' ' -> {
                    Unit
                }

                else -> {
                    throw LuaError.of("invalid format option '$option'")
                }
            }
        }
        return LuaBytes.wrap(out.toByteArray())
    }

    fun unpack(args: List<LuaValue>): List<LuaValue> {
        val format = args.checkBytes(0, "unpack").utf8()
        val data = args.checkBytes(1, "unpack").toByteArray()
        val state = State()
        var position = (args.optInteger(2, 1) - 1).toInt()
        val result = ArrayList<LuaValue>()
        var i = 0
        while (i < format.length) {
            val option = format[i]
            i++
            val size = readSize(format, i).also { i += it.consumed }.value
            when (option) {
                in CONTROL -> {
                    applyControl(option, state)
                }

                'b', 'B', 'h', 'H', 'i', 'I', 'l', 'L', 'j', 'J', 'T' -> {
                    val width = integerWidth(option, size)
                    result.add(LuaInteger(unpackInteger(data, position, width, option.isUpperCase(), state.order)))
                    position += width
                }

                'f' -> {
                    result.add(de.fiereu.lua.LuaFloat(unpackFloat(data, position, state.order).toDouble()))
                    position += 4
                }

                'd', 'n' -> {
                    result.add(de.fiereu.lua.LuaFloat(unpackDouble(data, position, state.order)))
                    position += 8
                }

                'z' -> {
                    var end = position
                    while (end < data.size && data[end].toInt() != 0) end++
                    result.add(LuaString(LuaBytes.of(data.copyOfRange(position, end))))
                    position = end + 1
                }

                'x' -> {
                    position += 1
                }

                'X' -> {
                    if (i < format.length) {
                        i++
                        i += readSize(format, i).consumed
                    }
                }

                ' ' -> {
                    Unit
                }

                else -> {
                    throw LuaError.of("invalid format option '$option'")
                }
            }
        }
        result.add(LuaInteger((position + 1).toLong()))
        return result
    }

    fun packSize(args: List<LuaValue>): Long {
        val format = args.checkBytes(0, "packsize").utf8()
        val state = State()
        var total = 0L
        var i = 0
        while (i < format.length) {
            val option = format[i]
            i++
            val size = readSize(format, i).also { i += it.consumed }.value
            total +=
                when (option) {
                    in CONTROL -> {
                        applyControl(option, state)
                        0
                    }

                    'b', 'B', 'h', 'H', 'i', 'I', 'l', 'L', 'j', 'J', 'T' -> {
                        integerWidth(option, size).toLong()
                    }

                    'f' -> {
                        4
                    }

                    'd', 'n' -> {
                        8
                    }

                    'x' -> {
                        1
                    }

                    'X' -> {
                        if (i < format.length) {
                            i++
                            i += readSize(format, i).consumed
                        }
                        0
                    }

                    ' ' -> {
                        0
                    }

                    's', 'z' -> {
                        throw LuaError.of("variable-size format in packsize")
                    }

                    else -> {
                        throw LuaError.of("invalid format option '$option'")
                    }
                }
        }
        return total
    }

    private class State {
        var order: ByteOrder = ByteOrder.nativeOrder()
    }

    private class Size(
        val value: Int,
        val consumed: Int,
    )

    private fun readSize(
        format: String,
        from: Int,
    ): Size {
        var i = from
        var value = 0
        var seen = false
        while (i < format.length && format[i].isDigit()) {
            value = value * 10 + (format[i] - '0')
            seen = true
            i++
        }
        return Size(if (seen) value else 0, i - from)
    }

    private fun applyControl(
        option: Char,
        state: State,
    ) {
        when (option) {
            '<' -> state.order = ByteOrder.LITTLE_ENDIAN
            '>' -> state.order = ByteOrder.BIG_ENDIAN
            '=' -> state.order = ByteOrder.nativeOrder()
            '!' -> Unit
            else -> Unit
        }
    }

    private fun integerWidth(
        option: Char,
        size: Int,
    ): Int =
        when (option.lowercaseChar()) {
            'b' -> 1
            'h' -> 2
            'i' -> if (size > 0) size else 4
            'l', 'j', 't' -> 8
            else -> 8
        }

    private fun packInteger(
        out: ByteArrayOutputStream,
        value: Long,
        width: Int,
        order: ByteOrder,
    ) {
        val ordered = ByteArray(width)
        for (k in 0 until width) {
            val byte = (value shr (8 * k)).toByte()
            ordered[if (order == ByteOrder.LITTLE_ENDIAN) k else width - 1 - k] = byte
        }
        out.write(ordered)
    }

    private fun unpackInteger(
        data: ByteArray,
        position: Int,
        width: Int,
        unsigned: Boolean,
        order: ByteOrder,
    ): Long {
        var value = 0L
        for (k in 0 until width) {
            val byte = data[position + if (order == ByteOrder.LITTLE_ENDIAN) k else width - 1 - k].toLong() and 0xFF
            value = value or (byte shl (8 * k))
        }
        if (!unsigned && width < 8) {
            val signBit = 1L shl (8 * width - 1)
            if (value and signBit != 0L) value -= 1L shl (8 * width)
        }
        return value
    }

    private fun packFloat(
        out: ByteArrayOutputStream,
        value: Float,
        order: ByteOrder,
    ) = packInteger(
        out,
        java.lang.Float
            .floatToRawIntBits(value)
            .toLong() and 0xFFFFFFFFL,
        4,
        order,
    )

    private fun packDouble(
        out: ByteArrayOutputStream,
        value: Double,
        order: ByteOrder,
    ) = packInteger(out, java.lang.Double.doubleToRawLongBits(value), 8, order)

    private fun unpackFloat(
        data: ByteArray,
        position: Int,
        order: ByteOrder,
    ): Float = java.lang.Float.intBitsToFloat(unpackInteger(data, position, 4, unsigned = true, order = order).toInt())

    private fun unpackDouble(
        data: ByteArray,
        position: Int,
        order: ByteOrder,
    ): Double = java.lang.Double.longBitsToDouble(unpackInteger(data, position, 8, unsigned = true, order = order))

    private fun packString(
        out: ByteArrayOutputStream,
        value: ByteArray,
        lengthSize: Int,
        order: ByteOrder,
    ) {
        packInteger(out, value.size.toLong(), lengthSize, order)
        out.write(value)
    }

    private const val CONTROL = "<>=!"
}
