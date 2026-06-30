package de.fiereu.lua.common

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** A numeral parsed from source: either a Lua integer or a Lua float. */
internal sealed interface LuaNumeral {
    data class Int(
        val value: Long,
    ) : LuaNumeral

    data class Float(
        val value: Double,
    ) : LuaNumeral
}

/**
 * Lua's number lexing and `tostring`/`tonumber` formatting rules (manual §3.1,
 * §3.4.3). Integers are 64-bit two's complement, floats are IEEE-754 doubles.
 */
internal object LuaNumbers {
    private const val FLOAT_PRECISION = 14

    private val hexInteger = Regex("[0-9a-fA-F]+")

    private fun isLuaSpace(c: Char): Boolean =
        when (c.code) {
            0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> true
            else -> false
        }

    /**
     * Parses a numeral exactly as the lexer sees it: no surrounding spaces and no
     * leading sign. Returns null when [text] is not a valid Lua numeral.
     */
    fun parseNumeral(text: String): LuaNumeral? {
        if (text.isEmpty()) return null
        return if (text.length > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
            parseHex(text.substring(2))
        } else {
            parseDecimal(text)
        }
    }

    /**
     * Parses a numeral for `tonumber`: optional surrounding whitespace and an
     * optional leading sign are allowed. Returns null when [text] is not a number.
     */
    fun parse(text: String): LuaNumeral? {
        val trimmed = text.trim(::isLuaSpace)
        if (trimmed.isEmpty()) return null
        val negative = trimmed[0] == '-'
        val body = if (trimmed[0] == '+' || trimmed[0] == '-') trimmed.substring(1) else trimmed
        val parsed = parseNumeral(body) ?: return null
        if (!negative) return parsed
        return when (parsed) {
            is LuaNumeral.Int -> LuaNumeral.Int(-parsed.value)
            is LuaNumeral.Float -> LuaNumeral.Float(-parsed.value)
        }
    }

    private fun parseDecimal(text: String): LuaNumeral? {
        val afterInteger = skipDigits(text, 0)
        var index = afterInteger
        val hasDot = index < text.length && text[index] == '.'
        if (hasDot) index = skipDigits(text, index + 1)
        val mantissaDigits = index - if (hasDot) 1 else 0
        if (mantissaDigits == 0) return null
        val hasExponent = index < text.length && (text[index] == 'e' || text[index] == 'E')
        if (hasExponent) index = skipExponent(text, index + 1) ?: return null
        if (index != text.length) return null
        if (hasDot || hasExponent) return LuaNumeral.Float(text.toDouble())
        val asLong = text.toLongOrNull()
        return if (asLong != null) LuaNumeral.Int(asLong) else LuaNumeral.Float(text.toDouble())
    }

    private fun skipDigits(
        text: String,
        from: Int,
    ): Int {
        var index = from
        while (index < text.length && text[index] in '0'..'9') index++
        return index
    }

    private fun skipExponent(
        text: String,
        from: Int,
    ): Int? {
        var index = from
        if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
        val afterDigits = skipDigits(text, index)
        return if (afterDigits == index) null else afterDigits
    }

    private fun parseHex(rest: String): LuaNumeral? {
        if (rest.isEmpty()) return null
        val isFloat = rest.any { it == '.' || it == 'p' || it == 'P' }
        return if (isFloat) parseHexFloat(rest) else parseHexInteger(rest)
    }

    private fun parseHexInteger(rest: String): LuaNumeral? {
        if (!hexInteger.matches(rest)) return null
        var acc = 0L
        for (c in rest) {
            acc = acc * 16 + Character.digit(c, 16)
        }
        return LuaNumeral.Int(acc)
    }

    private fun parseHexFloat(rest: String): LuaNumeral? {
        var mantissa = 0.0
        var fractionScale = 1.0
        var seenDigit = false
        var seenDot = false
        var index = 0
        while (index < rest.length) {
            val c = rest[index]
            when {
                c == '.' -> {
                    if (seenDot) return null
                    seenDot = true
                }

                c == 'p' || c == 'P' -> {
                    break
                }

                isHexDigit(c) -> {
                    seenDigit = true
                    val digit = Character.digit(c, 16)
                    if (seenDot) {
                        fractionScale /= 16.0
                        mantissa += digit * fractionScale
                    } else {
                        mantissa = mantissa * 16.0 + digit
                    }
                }

                else -> {
                    return null
                }
            }
            index++
        }
        if (!seenDigit) return null
        var exponent = 0
        if (index < rest.length) {
            val exponentText = rest.substring(index + 1)
            exponent = exponentText.toIntOrNull() ?: return null
        }
        return LuaNumeral.Float(mantissa * Math.scalb(1.0, exponent))
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Renders an integer as Lua's `tostring` would. */
    fun integerToString(value: Long): String = value.toString()

    /** Renders a float as Lua's `tostring` would, using the `%.14g` format plus the `.0` rule. */
    fun floatToString(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val formatted = formatG(value, FLOAT_PRECISION)
        return if (looksLikeInteger(formatted)) "$formatted.0" else formatted
    }

    private fun looksLikeInteger(text: String): Boolean {
        val body = if (text.startsWith("-")) text.substring(1) else text
        return body.isNotEmpty() && body.all { it in '0'..'9' }
    }

    /** A C-library `%.Pg` emulation: shortest of fixed/scientific with trailing zeros trimmed. */
    private fun formatG(
        value: Double,
        precision: Int,
    ): String {
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val negative = value < 0
        val magnitude = abs(value)
        val rounded = BigDecimal(magnitude).round(MathContext(precision, RoundingMode.HALF_EVEN))
        val exponent = rounded.precision() - rounded.scale() - 1
        val body =
            if (exponent < -4 || exponent >= precision) {
                val mantissa = stripTrailingZeros(rounded.movePointLeft(exponent).toPlainString())
                val sign = if (exponent >= 0) "+" else "-"
                val magnitudeDigits = abs(exponent).toString().padStart(2, '0')
                "${mantissa}e$sign$magnitudeDigits"
            } else {
                stripTrailingZeros(rounded.toPlainString())
            }
        return if (negative) "-$body" else body
    }

    private fun stripTrailingZeros(text: String): String {
        if (!text.contains('.')) return text
        var end = text.length
        while (end > 0 && text[end - 1] == '0') end--
        if (end > 0 && text[end - 1] == '.') end--
        return text.substring(0, end)
    }
}
