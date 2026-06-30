package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaCoroutine
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.arg
import de.fiereu.lua.luaTypeName
import de.fiereu.lua.runtime.CoroutineSupport

/** The coroutine library, built on the runtime's virtual-thread coroutines. */
internal object CoroutineLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "coroutine") {
        result("create") { runtime.newCoroutine(checkBody(it)) }
        function("resume") { resume(it) }
        function("yield") { CoroutineSupport.yield(it) }
        result("status") { LuaString.of(statusName(checkCoroutine(it).status)) }
        result("wrap") { wrap(runtime, it) }
        result("isyieldable") { LuaBoolean.of(CoroutineSupport.currentOrNull() != null) }
        function("running") { running() }
        function("close") { close(it) }
    }

    private fun close(args: List<LuaValue>): List<LuaValue> {
        val coroutine =
            args.arg(0) as? de.fiereu.lua.runtime.LuaCoroutineImpl
                ?: badArgument(0, "close", "coroutine expected, got ${args.arg(0).luaTypeName}")
        return coroutine.close()
    }

    private fun checkBody(args: List<LuaValue>): LuaFunction =
        args.arg(0) as? LuaFunction ?: badArgument(0, "create", "function expected, got ${args.arg(0).luaTypeName}")

    private fun checkCoroutine(args: List<LuaValue>): LuaCoroutine =
        args.arg(0) as? LuaCoroutine ?: badArgument(0, "status", "coroutine expected, got ${args.arg(0).luaTypeName}")

    private fun resume(args: List<LuaValue>): List<LuaValue> {
        val coroutine = args.arg(0) as? LuaCoroutine ?: badArgument(0, "resume", "coroutine expected, got ${args.arg(0).luaTypeName}")
        return try {
            listOf(LuaBoolean.TRUE) + coroutine.resume(args.drop(1))
        } catch (error: LuaError) {
            listOf(LuaBoolean.FALSE, error.value)
        }
    }

    private fun wrap(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): LuaFunction {
        val coroutine = runtime.newCoroutine(checkBody(args))
        return LuaFunction { arguments -> coroutine.resume(arguments) }
    }

    private fun running(): List<LuaValue> {
        val current = CoroutineSupport.currentOrNull()
        return if (current != null) listOf(current, LuaBoolean.FALSE) else listOf(LuaNil, LuaBoolean.TRUE)
    }

    private fun statusName(status: LuaCoroutine.Status): String =
        when (status) {
            LuaCoroutine.Status.SUSPENDED -> "suspended"
            LuaCoroutine.Status.RUNNING -> "running"
            LuaCoroutine.Status.NORMAL -> "normal"
            LuaCoroutine.Status.DEAD -> "dead"
        }
}
