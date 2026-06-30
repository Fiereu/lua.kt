package de.fiereu.lua

import de.fiereu.lua.stdlib.BaseLibrary
import de.fiereu.lua.stdlib.CoroutineLibrary
import de.fiereu.lua.stdlib.DebugExLibrary
import de.fiereu.lua.stdlib.DebugLibrary
import de.fiereu.lua.stdlib.IoLibrary
import de.fiereu.lua.stdlib.MathLibrary
import de.fiereu.lua.stdlib.OsLibrary
import de.fiereu.lua.stdlib.PackageLibrary
import de.fiereu.lua.stdlib.StringLibrary
import de.fiereu.lua.stdlib.TableLibrary
import de.fiereu.lua.stdlib.Utf8Library

/** Built-in standard libraries, each switchable for sandboxing. */
public object StdLibs {
    public val Base: LuaLibrary = BaseLibrary
    public val Package: LuaLibrary = PackageLibrary
    public val StringLib: LuaLibrary = StringLibrary
    public val TableLib: LuaLibrary = TableLibrary
    public val MathLib: LuaLibrary = MathLibrary
    public val Utf8Lib: LuaLibrary = Utf8Library
    public val Coroutine: LuaLibrary = CoroutineLibrary
    public val OsLib: LuaLibrary = OsLibrary
    public val IoLib: LuaLibrary = IoLibrary
    public val DebugLib: LuaLibrary = DebugLibrary

    /** The richer debugging library: real stack/locals plus breakpoints, eval, and profiling. */
    public val DebugExLib: LuaLibrary = DebugExLibrary

    /** Safe set for untrusted scripts: no io, no os. */
    public val SAFE_DEFAULT: Set<LuaLibrary> = setOf(Base, Package, StringLib, TableLib, MathLib, Utf8Lib, Coroutine)

    /** Everything a standalone interpreter would expose. */
    public val ALL: Set<LuaLibrary> = SAFE_DEFAULT + setOf(OsLib, IoLib, DebugLib, DebugExLib)
}
