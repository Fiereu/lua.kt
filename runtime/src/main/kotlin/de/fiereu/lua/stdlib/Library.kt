package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.runtime.Interpreter
import de.fiereu.lua.set

/**
 * Receiver for the library-definition DSL. Each [LuaLibrary] populates its table
 * through one of these, which trims the repetitive
 * `table["name"] = LuaFunction { args -> listOf(...) }` boilerplate.
 */
internal class LibraryScope(
    val runtime: LuaRuntime,
    val table: LuaTable,
) {
    val interpreter: Interpreter get() = runtime.interpreter

    /** Registers a function whose [body] produces a single value, wrapped for Lua's multi-return. */
    fun result(
        name: String,
        body: (List<LuaValue>) -> LuaValue,
    ) {
        table[name] = LuaFunction { args -> listOf(body(args)) }
    }

    /** Registers a function whose [body] produces the result list directly. */
    fun function(
        name: String,
        body: (List<LuaValue>) -> List<LuaValue>,
    ) {
        table[name] = LuaFunction(body)
    }

    /** Stores a plain field value such as `math.pi` or `utf8.charpattern`. */
    fun constant(
        name: String,
        value: LuaValue,
    ) {
        table[name] = value
    }

    /** Registers `{ __index = table }` as the metatable for values of [typeName], for example `string`. */
    fun exposeAsTypeMetatable(typeName: String) {
        val metatable = runtime.newTable()
        metatable["__index"] = table
        interpreter.setTypeMetatable(typeName, metatable)
    }
}

/** Creates a fresh module table, stores it under `target[name]`, and populates it via [block]. */
internal fun LuaRuntime.module(
    target: LuaTable,
    name: String,
    block: LibraryScope.() -> Unit,
) {
    val moduleTable = newTable()
    target[name] = moduleTable
    LibraryScope(this, moduleTable).block()
}

/** Populates an already existing [table], for globals or interdependent sub-tables. */
internal fun LuaRuntime.populate(
    table: LuaTable,
    block: LibraryScope.() -> Unit,
) {
    LibraryScope(this, table).block()
}
