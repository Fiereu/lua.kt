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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/** The math library. */
internal object MathLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "math") {
        val random = Random(SEED)

        constant("pi", LuaFloat(Math.PI))
        constant("huge", LuaFloat(Double.POSITIVE_INFINITY))
        constant("maxinteger", LuaInteger(Long.MAX_VALUE))
        constant("mininteger", LuaInteger(Long.MIN_VALUE))

        result("abs") { absolute(it.arg(0)) }
        result("ceil") { toIntegerResult(Math.ceil(it.checkNumber(0, "ceil")), it.arg(0)) }
        result("floor") { toIntegerResult(Math.floor(it.checkNumber(0, "floor")), it.arg(0)) }
        result("sqrt", unary("sqrt", Math::sqrt))
        result("sin", unary("sin", Math::sin))
        result("cos", unary("cos", Math::cos))
        result("tan", unary("tan", Math::tan))
        result("asin", unary("asin", Math::asin))
        result("acos", unary("acos", Math::acos))
        result("atan") { LuaFloat(Math.atan2(it.checkNumber(0, "atan"), it.optNumber(1, 1.0))) }
        result("exp", unary("exp", Math::exp))
        result("deg", unary("deg", Math::toDegrees))
        result("rad", unary("rad", Math::toRadians))
        result("log") { LuaFloat(logarithm(it)) }
        result("fmod") { LuaFloat(it.checkNumber(0, "fmod") % it.checkNumber(1, "fmod")) }
        function("modf") { modf(it.checkNumber(0, "modf")) }
        result("max") { extreme(it, "max", takeGreater = true) }
        result("min") { extreme(it, "min", takeGreater = false) }
        result("tointeger") { integerOf(it.arg(0))?.let { value -> LuaInteger(value) } ?: LuaNil }
        result("type") { numberType(it.arg(0)) }
        result("ult") {
            de.fiereu.lua.LuaBoolean
                .of(java.lang.Long.compareUnsigned(it.checkInteger(0, "ult"), it.checkInteger(1, "ult")) < 0)
        }
        result("random") { randomValue(random, it) }
        function("randomseed") { emptyList() }
    }

    private fun unary(
        name: String,
        function: (Double) -> Double,
    ): (List<LuaValue>) -> LuaValue = { args -> LuaFloat(function(args.checkNumber(0, name))) }

    private fun absolute(value: LuaValue): LuaValue =
        when (value) {
            is LuaInteger -> LuaInteger(abs(value.value))
            is LuaFloat -> LuaFloat(abs(value.value))
            else -> LuaFloat(abs(numberOf(value) ?: badArgument(0, "abs", NUMBER_EXPECTED)))
        }

    private fun toIntegerResult(
        rounded: Double,
        original: LuaValue,
    ): LuaValue {
        if (original is LuaInteger) return original
        if (rounded >= Long.MIN_VALUE.toDouble() && rounded <= Long.MAX_VALUE.toDouble()) {
            return LuaInteger(rounded.toLong())
        }
        throw LuaError.of("number has no integer representation")
    }

    private fun logarithm(args: List<LuaValue>): Double {
        val x = args.checkNumber(0, "log")
        val base = args.arg(1)
        if (base == LuaNil) return ln(x)
        return ln(x) / ln(numberOf(base) ?: badArgument(1, "log", NUMBER_EXPECTED))
    }

    private fun modf(value: Double): List<LuaValue> {
        val integral = if (value < 0) Math.ceil(value) else Math.floor(value)
        val fraction = if (value.isInfinite()) 0.0 else value - integral
        return listOf(LuaFloat(integral), LuaFloat(fraction))
    }

    private fun extreme(
        args: List<LuaValue>,
        name: String,
        takeGreater: Boolean,
    ): LuaValue {
        if (args.isEmpty()) badArgument(0, name, "number expected, got no value")
        var best = args[0]
        var bestValue = numberOf(best) ?: badArgument(0, name, NUMBER_EXPECTED)
        for (index in 1 until args.size) {
            val candidate = numberOf(args[index]) ?: badArgument(index, name, NUMBER_EXPECTED)
            if (if (takeGreater) candidate > bestValue else candidate < bestValue) {
                best = args[index]
                bestValue = candidate
            }
        }
        return best
    }

    private fun numberType(value: LuaValue): LuaValue =
        when (value) {
            is LuaInteger -> LuaString.of("integer")
            is LuaFloat -> LuaString.of("float")
            else -> LuaNil
        }

    private fun randomValue(
        random: Random,
        args: List<LuaValue>,
    ): LuaValue =
        when (args.size) {
            0 -> {
                LuaFloat(random.nextDouble())
            }

            1 -> {
                LuaInteger(random.nextLong(1, args.checkInteger(0, "random") + 1))
            }

            else -> {
                val low = args.checkInteger(0, "random")
                val high = args.checkInteger(1, "random")
                LuaInteger(random.nextLong(low, high + 1))
            }
        }

    private fun List<LuaValue>.optNumber(
        index: Int,
        default: Double,
    ): Double {
        val value = arg(index)
        if (value == LuaNil) return default
        return numberOf(value) ?: badArgument(index, "atan", NUMBER_EXPECTED)
    }

    private const val SEED = 0x2545F4914F6CDD1DL
    private const val NUMBER_EXPECTED = "number expected"
}
