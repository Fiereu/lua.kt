package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg
import de.fiereu.lua.luaTypeName

/**
 * The debug library (manual §6.11). Backed by the interpreter's call-frame stack,
 * so traceback, getinfo, get/setlocal, get/setupvalue, and hooks are real. The
 * richer [DebugExLibrary] adds breakpoints, in-frame eval, and profiling.
 */
internal object DebugLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "debug") {
        val registry = runtime.newTable()

        result("getmetatable") { interpreter.metatableOf(it.arg(0)) ?: LuaNil }
        function("setmetatable") { debugSetMetatable(runtime, it) }
        result("traceback") { debugTraceback(runtime, it) }
        result("getinfo") { debugGetInfo(runtime, it) }
        result("getregistry") { registry }
        function("getlocal") { debugGetLocal(runtime, it) }
        function("setlocal") { debugSetLocal(runtime, it) }
        function("getupvalue") { debugGetUpvalue(it) }
        function("setupvalue") { debugSetUpvalue(it) }
        result("upvalueid") { runtime.newUserdata(upvalueToken(), null) }
        function("upvaluejoin") { emptyList() }
        function("getuservalue") { listOf(LuaNil, LuaBoolean.FALSE) }
        result("setuservalue") { it.arg(0) }
        function("gethook") { debugGetHook(runtime) }
        function("sethook") { debugSetHook(runtime, it) }
        function("debug") { emptyList() }
    }

    /** A unique marker used as the userdata behind `debug.upvalueid`. */
    private fun upvalueToken(): Any = Any()
}
