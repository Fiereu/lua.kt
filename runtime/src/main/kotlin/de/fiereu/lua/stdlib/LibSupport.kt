package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.SecurityAction
import de.fiereu.lua.SecurityRequest
import de.fiereu.lua.arg
import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral
import de.fiereu.lua.luaTypeName
import de.fiereu.lua.runtime.Interpreter
import de.fiereu.lua.runtime.LuaRuntimeImpl
import java.io.File

internal val LuaRuntime.interpreter: Interpreter
    get() = (this as LuaRuntimeImpl).interpreter

/**
 * Consults the configured security policy (if any) before a sensitive operation.
 * Raises a [LuaError] when the policy denies it. A no-op when no policy is set.
 */
internal fun LuaRuntime.guard(
    action: SecurityAction,
    target: String,
    secondaryTarget: String? = null,
) {
    val policy = (this as LuaRuntimeImpl).config.securityPolicy ?: return
    val resolved = if (isFileAction(action)) File(target).absoluteFile.normalize().path else null
    if (!policy.isAllowed(SecurityRequest(action, target, resolved, secondaryTarget))) {
        throw LuaError.of("operation not permitted: ${verbFor(action)} '$target'")
    }
}

private fun isFileAction(action: SecurityAction): Boolean =
    action == SecurityAction.READ_FILE ||
        action == SecurityAction.WRITE_FILE ||
        action == SecurityAction.DELETE_FILE ||
        action == SecurityAction.RENAME_FILE ||
        action == SecurityAction.TEMP_FILE

private fun verbFor(action: SecurityAction): String =
    when (action) {
        SecurityAction.READ_FILE -> "read"
        SecurityAction.WRITE_FILE -> "write"
        SecurityAction.DELETE_FILE -> "delete"
        SecurityAction.RENAME_FILE -> "rename"
        SecurityAction.TEMP_FILE -> "create temp file"
        SecurityAction.EXECUTE -> "execute"
        SecurityAction.EXIT -> "exit"
        SecurityAction.READ_ENV -> "read env"
        SecurityAction.LOAD_CODE -> "load"
        SecurityAction.LOAD_MODULE -> "require"
    }

internal fun badArgument(
    index: Int,
    name: String,
    detail: String,
): Nothing = throw LuaError.of("bad argument #${index + 1} to '$name' ($detail)")

internal fun List<LuaValue>.checkTable(
    index: Int,
    name: String,
): LuaTable =
    arg(index) as? LuaTable
        ?: badArgument(index, name, "table expected, got ${arg(index).luaTypeName}")

internal fun List<LuaValue>.checkFunction(
    index: Int,
    name: String,
): LuaValue {
    val value = arg(index)
    if (value is de.fiereu.lua.LuaFunction) return value
    badArgument(index, name, "function expected, got ${value.luaTypeName}")
}

internal fun List<LuaValue>.checkBytes(
    index: Int,
    name: String,
): LuaBytes =
    when (val value = arg(index)) {
        is LuaString -> value.bytes
        is LuaInteger -> LuaBytes.of(LuaNumbers.integerToString(value.value))
        is LuaFloat -> LuaBytes.of(LuaNumbers.floatToString(value.value))
        else -> badArgument(index, name, "string expected, got ${value.luaTypeName}")
    }

internal fun List<LuaValue>.checkNumber(
    index: Int,
    name: String,
): Double = numberOf(arg(index)) ?: badArgument(index, name, "number expected, got ${arg(index).luaTypeName}")

internal fun List<LuaValue>.checkInteger(
    index: Int,
    name: String,
): Long = integerOf(arg(index)) ?: badArgument(index, name, "number expected, got ${arg(index).luaTypeName}")

internal fun List<LuaValue>.optInteger(
    index: Int,
    default: Long,
): Long {
    val value = arg(index)
    if (value == LuaNil) return default
    return integerOf(value) ?: default
}

internal fun numberOf(value: LuaValue): Double? =
    when (value) {
        is LuaInteger -> {
            value.value.toDouble()
        }

        is LuaFloat -> {
            value.value
        }

        is LuaString -> {
            when (val parsed = LuaNumbers.parse(value.bytes.utf8())) {
                is LuaNumeral.Int -> parsed.value.toDouble()
                is LuaNumeral.Float -> parsed.value
                null -> null
            }
        }

        else -> {
            null
        }
    }

internal fun integerOf(value: LuaValue): Long? =
    when (value) {
        is LuaInteger -> {
            value.value
        }

        is LuaFloat -> {
            value.value.toLong().takeIf { it.toDouble() == value.value && !value.value.isInfinite() }
        }

        is LuaString -> {
            when (val parsed = LuaNumbers.parse(value.bytes.utf8())) {
                is LuaNumeral.Int -> parsed.value
                is LuaNumeral.Float -> parsed.value.toLong().takeIf { it.toDouble() == parsed.value }
                null -> null
            }
        }

        else -> {
            null
        }
    }

internal fun luaString(text: String): LuaString = LuaString.of(text)

internal fun ret(vararg values: LuaValue): List<LuaValue> = values.toList()
