package de.fiereu.lua.runtime

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue

/**
 * The default [LuaTable]. Entries live in a single insertion-ordered map so that
 * `next` iteration is stable. Float keys with an integral value are normalized to
 * integer keys, matching Lua's key semantics.
 */
internal class LuaTableImpl : LuaTable {
    private val entries = LinkedHashMap<LuaValue, LuaValue>()
    override var metatable: LuaTable? = null

    override fun rawGet(key: LuaValue): LuaValue = entries[normalizeKey(key)] ?: LuaNil

    override fun rawSet(
        key: LuaValue,
        value: LuaValue,
    ) {
        val normalized = normalizeKey(key)
        when {
            normalized == LuaNil -> throw LuaError.of("table index is nil")
            normalized is LuaFloat && normalized.value.isNaN() -> throw LuaError.of("table index is NaN")
        }
        if (value == LuaNil) {
            entries.remove(normalized)
        } else {
            entries[normalized] = value
        }
    }

    override fun get(key: LuaValue): LuaValue {
        val raw = rawGet(key)
        if (raw != LuaNil) return raw
        val handler = metatable?.rawGet(INDEX) ?: LuaNil
        return when (handler) {
            LuaNil -> LuaNil
            is LuaFunction -> handler.call(listOf(this, key)).firstOrNull() ?: LuaNil
            is LuaTable -> handler[key]
            else -> LuaNil
        }
    }

    override fun set(
        key: LuaValue,
        value: LuaValue,
    ) {
        if (rawGet(key) != LuaNil) {
            rawSet(key, value)
            return
        }
        when (val handler = metatable?.rawGet(NEWINDEX) ?: LuaNil) {
            LuaNil -> rawSet(key, value)
            is LuaFunction -> handler.call(listOf(this, key, value))
            is LuaTable -> handler[key] = value
            else -> rawSet(key, value)
        }
    }

    override val length: Long
        get() {
            if (rawGet(LuaInteger(1)) == LuaNil) return 0
            var present = 1L
            var absent = 2L
            while (rawGet(LuaInteger(absent)) != LuaNil) {
                present = absent
                if (absent > Long.MAX_VALUE / 2) return linearBorder()
                absent *= 2
            }
            while (absent - present > 1) {
                val middle = (present + absent) ushr 1
                if (rawGet(LuaInteger(middle)) == LuaNil) absent = middle else present = middle
            }
            return present
        }

    private fun linearBorder(): Long {
        var n = 1L
        while (rawGet(LuaInteger(n)) != LuaNil) n++
        return n - 1
    }

    override fun entries(): Sequence<Pair<LuaValue, LuaValue>> = entries.entries.asSequence().map { it.key to it.value }

    private companion object {
        val INDEX = LuaString.of("__index")
        val NEWINDEX = LuaString.of("__newindex")

        fun normalizeKey(key: LuaValue): LuaValue {
            if (key is LuaFloat) {
                val asLong = key.value.toLong()
                if (asLong.toDouble() == key.value) return LuaInteger(asLong)
            }
            return key
        }
    }
}
