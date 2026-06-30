package de.fiereu.lua.runtime

import de.fiereu.lua.LuaValue

/** A mutable variable slot. Closures capture cells so upvalues are shared. */
internal class Cell(
    var value: LuaValue,
    val readOnly: Boolean = false,
)

/** A lexical scope: a set of named cells chained to its enclosing scope. */
internal class Scope(
    private val parent: Scope?,
) {
    private val variables = LinkedHashMap<String, Cell>()

    /** The enclosing scope, or null for a function's outermost scope. */
    val enclosing: Scope?
        get() = parent

    fun define(
        name: String,
        value: LuaValue,
        readOnly: Boolean = false,
    ): Cell {
        val cell = Cell(value, readOnly)
        variables[name] = cell
        return cell
    }

    fun resolve(name: String): Cell? = variables[name] ?: parent?.resolve(name)

    /** Cells declared directly in this scope, in declaration order. Used by the debugger. */
    fun localCells(): List<Pair<String, Cell>> = variables.entries.map { it.key to it.value }

    /** A cell declared directly in this scope, without walking up to [enclosing]. */
    fun localCell(name: String): Cell? = variables[name]
}
