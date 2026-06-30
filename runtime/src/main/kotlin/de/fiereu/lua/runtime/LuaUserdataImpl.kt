package de.fiereu.lua.runtime

import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaUserdata

/** The default [LuaUserdata]: a host object with an optional metatable. */
internal class LuaUserdataImpl(
    override val instance: Any,
    override var metatable: LuaTable?,
) : LuaUserdata
