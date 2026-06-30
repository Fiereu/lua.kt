package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg

/**
 * `debugEx`: a richer debugging library built on the interpreter's call-frame
 * stack. It is a superset of `debug`, adding all-locals/upvalues tables, in-frame
 * `eval`, source breakpoints, and a simple profiler. It pairs with the host-side
 * `Debugger` API for breakpoint handling and stepping.
 */
internal object DebugExLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "debugEx") {
        val registry = runtime.newTable()

        result("getmetatable") { interpreter.metatableOf(it.arg(0)) ?: LuaNil }
        function("setmetatable") { debugSetMetatable(runtime, it) }
        result("getregistry") { registry }
        result("traceback") { debugTraceback(runtime, it) }
        result("getinfo") { debugGetInfo(runtime, it) }
        function("getlocal") { debugGetLocal(runtime, it) }
        function("setlocal") { debugSetLocal(runtime, it) }
        function("getupvalue") { debugGetUpvalue(it) }
        function("setupvalue") { debugSetUpvalue(it) }
        function("gethook") { debugGetHook(runtime) }
        function("sethook") { debugSetHook(runtime, it) }

        result("locals") { debugLocalsTable(runtime, it.checkInteger(0, "locals").toInt()) }
        result("upvalues") { debugUpvaluesTable(runtime, it) }
        result("stackdepth") { LuaInteger(runtime.interpreter.currentDepth().toLong()) }
        function("eval") { eval(runtime, it) }
        function("setbreakpoint") { setBreakpoint(runtime, it) }
        result("clearbreakpoint") {
            LuaBoolean.of(runtime.interpreter.debugController().removeBreakpoint(it.checkInteger(0, "clearbreakpoint").toInt()))
        }
        result("breakpoints") { debugBreakpointsTable(runtime) }

        val profile = runtime.newTable()
        constant("profile", profile)
        runtime.populate(profile) {
            function("start") {
                runtime.interpreter.debugController().startProfiling()
                emptyList()
            }
            function("stop") {
                runtime.interpreter.debug?.stopProfiling()
                emptyList()
            }
            function("reset") {
                runtime.interpreter.debug?.resetProfiling()
                emptyList()
            }
            result("report") { debugProfileReport(runtime) }
        }
    }

    private fun eval(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val level = args.checkInteger(0, "eval").toInt()
        val code = args.checkBytes(1, "eval").utf8()
        val frame = runtime.interpreter.frameAtLevel(level) ?: throw LuaError.of("no function at level $level")
        return runtime.interpreter.evalInScope(code, frame.currentScope)
    }

    private fun setBreakpoint(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val source = args.checkBytes(0, "setbreakpoint").utf8()
        val line = args.checkInteger(1, "setbreakpoint").toInt()
        val condition = (args.arg(2) as? LuaString)?.bytes?.utf8()
        val breakpoint = runtime.interpreter.debugController().addBreakpoint(source, line, condition)
        return listOf(LuaInteger(breakpoint.id.toLong()))
    }
}
