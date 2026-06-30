package de.fiereu.lua

import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral

/** The nearest Kotlin equivalent of a Lua value. Reference types pass through unchanged. */
public fun LuaValue.toKotlin(): Any? =
    when (this) {
        is LuaNil -> null
        is LuaBoolean -> value
        is LuaInteger -> value
        is LuaFloat -> value
        is LuaString -> bytes.utf8()
        else -> this
    }

/** Lift a Kotlin value into a Lua value. */
public fun LuaRuntime.luaValueOf(value: Any?): LuaValue =
    when (value) {
        null -> LuaNil
        is LuaValue -> value
        is Boolean -> LuaBoolean.of(value)
        is Int -> LuaInteger(value.toLong())
        is Long -> LuaInteger(value)
        is Short -> LuaInteger(value.toLong())
        is Byte -> LuaInteger(value.toLong())
        is Double -> LuaFloat(value)
        is Float -> LuaFloat(value.toDouble())
        is String -> LuaString.of(value)
        else -> throw IllegalArgumentException("cannot convert ${value::class.qualifiedName} to a Lua value")
    }

/** The argument at [index], or `nil` if absent. */
public fun List<LuaValue>.arg(index: Int): LuaValue = getOrElse(index) { LuaNil }

/** A string view of the value, applying Lua's number-to-string coercion. */
public fun LuaValue.asStringOrNull(): String? =
    when (this) {
        is LuaString -> bytes.utf8()
        is LuaInteger -> LuaNumbers.integerToString(value)
        is LuaFloat -> LuaNumbers.floatToString(value)
        else -> null
    }

/** A long view of the value, applying Lua's string-to-number coercion. */
public fun LuaValue.asLongOrNull(): Long? =
    when (this) {
        is LuaInteger -> value
        is LuaFloat -> value.toLong().takeIf { it.toDouble() == value && !value.isInfinite() }
        is LuaString -> bytes.utf8().toNumeralOrNull()?.asLongOrNull()
        else -> null
    }

/** A double view of the value, applying Lua's string-to-number coercion. */
public fun LuaValue.asDoubleOrNull(): Double? =
    when (this) {
        is LuaInteger -> {
            value.toDouble()
        }

        is LuaFloat -> {
            value
        }

        is LuaString -> {
            when (val parsed = LuaNumbers.parse(bytes.utf8())) {
                is LuaNumeral.Int -> parsed.value.toDouble()
                is LuaNumeral.Float -> parsed.value
                null -> null
            }
        }

        else -> {
            null
        }
    }

private fun String.toNumeralOrNull(): LuaValue? =
    when (val parsed = LuaNumbers.parse(this)) {
        is LuaNumeral.Int -> LuaInteger(parsed.value)
        is LuaNumeral.Float -> LuaFloat(parsed.value)
        null -> null
    }
