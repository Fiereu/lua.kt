package de.fiereu.lua

import de.fiereu.lua.common.LuaNumbers

/**
 * A Lua error carrying an arbitrary error value (usually a string, but it can be
 * any Lua value such as a table). [traceback] is filled in where available.
 */
public class LuaError(
    public val value: LuaValue,
    public val traceback: String? = null,
    public val level: Int = 1,
    cause: Throwable? = null,
) : RuntimeException(describe(value), cause) {
    public companion object {
        public fun of(message: String): LuaError = LuaError(LuaString.of(message))

        private fun describe(value: LuaValue): String =
            when (value) {
                is LuaString -> value.bytes.utf8()
                is LuaInteger -> LuaNumbers.integerToString(value.value)
                is LuaFloat -> LuaNumbers.floatToString(value.value)
                else -> value.luaTypeName
            }
    }
}
