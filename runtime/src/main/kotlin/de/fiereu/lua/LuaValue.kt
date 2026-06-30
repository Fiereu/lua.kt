package de.fiereu.lua

import de.fiereu.lua.common.LuaBytes

/**
 * A Lua value. The hierarchy is sealed so an exhaustive `when` covers every Lua
 * type. Numbers split into [LuaInteger] and [LuaFloat] as in Lua 5.3 and later.
 */
public sealed interface LuaValue

/** The single `nil` value. */
public data object LuaNil : LuaValue

/** A boolean. Only `nil` and `false` are falsy in Lua. */
public class LuaBoolean private constructor(
    public val value: Boolean,
) : LuaValue {
    public companion object {
        public val TRUE: LuaBoolean = LuaBoolean(true)
        public val FALSE: LuaBoolean = LuaBoolean(false)

        public fun of(value: Boolean): LuaBoolean = if (value) TRUE else FALSE
    }
}

/** A 64-bit integer. */
public data class LuaInteger(
    val value: Long,
) : LuaValue

/** A 64-bit IEEE-754 float. */
public data class LuaFloat(
    val value: Double,
) : LuaValue

/** A byte string. */
public data class LuaString(
    val bytes: LuaBytes,
) : LuaValue {
    public companion object {
        public fun of(text: String): LuaString = LuaString(LuaBytes.of(text))
    }
}

/**
 * A Lua table: array and hash parts in one. Created through [LuaRuntime.newTable].
 * [get]/[set] honor the `__index`/`__newindex` metamethods, while [rawGet]/[rawSet]
 * bypass them (matching Lua's `rawget`/`rawset`).
 */
public interface LuaTable : LuaValue {
    public operator fun get(key: LuaValue): LuaValue

    public operator fun set(
        key: LuaValue,
        value: LuaValue,
    )

    public fun rawGet(key: LuaValue): LuaValue

    public fun rawSet(
        key: LuaValue,
        value: LuaValue,
    )

    /** The `#` border. Not necessarily the element count. */
    public val length: Long

    public var metatable: LuaTable?

    /** Stable iteration over every key/value pair, matching `next`. */
    public fun entries(): Sequence<Pair<LuaValue, LuaValue>>
}

/**
 * A callable value. Both Lua closures and native Kotlin functions implement this.
 * Arguments come in as a list, results go out as a list (Lua has multiple returns).
 */
public fun interface LuaFunction : LuaValue {
    public fun call(arguments: List<LuaValue>): List<LuaValue>
}

/** An opaque host object passed through Lua, with an optional controlling metatable. */
public interface LuaUserdata : LuaValue {
    public val instance: Any

    public var metatable: LuaTable?
}

/** A coroutine: a suspendable thread of Lua execution. */
public interface LuaCoroutine : LuaValue {
    public enum class Status { SUSPENDED, RUNNING, NORMAL, DEAD }

    public val status: Status

    public fun resume(arguments: List<LuaValue>): List<LuaValue>
}

/** Truthiness: everything except `nil` and `false` is truthy. */
public val LuaValue.isTruthy: Boolean
    get() = this != LuaNil && this != LuaBoolean.FALSE

/** The Lua type name used by `type()` and error messages. */
public val LuaValue.luaTypeName: String
    get() =
        when (this) {
            is LuaNil -> "nil"
            is LuaBoolean -> "boolean"
            is LuaInteger, is LuaFloat -> "number"
            is LuaString -> "string"
            is LuaTable -> "table"
            is LuaFunction -> "function"
            is LuaUserdata -> "userdata"
            is LuaCoroutine -> "thread"
        }

/** Convenience accessors for common key types. */
public operator fun LuaTable.get(key: String): LuaValue = get(LuaString.of(key))

public operator fun LuaTable.set(
    key: String,
    value: LuaValue,
): Unit = set(LuaString.of(key), value)

public operator fun LuaTable.get(index: Long): LuaValue = get(LuaInteger(index))

public operator fun LuaTable.set(
    index: Long,
    value: LuaValue,
): Unit = set(LuaInteger(index), value)
