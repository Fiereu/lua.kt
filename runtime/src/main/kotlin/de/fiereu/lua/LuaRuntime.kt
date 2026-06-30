package de.fiereu.lua

import de.fiereu.lua.runtime.LuaRuntimeImpl

/**
 * The entry point for embedding Lua. One instance is roughly one `lua_State`:
 * its own globals and loaded libraries. Not thread-safe, like Lua.
 */
public interface LuaRuntime : AutoCloseable {
    /** The global environment (`_G`). */
    public val globals: LuaTable

    /** Compile source without running it. The result is the chunk function. */
    public fun load(
        source: String,
        chunkName: String = "=(load)",
    ): LuaFunction

    /** Compile byte-string source (binary or Latin-1 content). */
    public fun load(
        source: ByteArray,
        chunkName: String,
    ): LuaFunction

    /** Compile and immediately run, returning the chunk's results. */
    public fun execute(
        source: String,
        chunkName: String = "=(load)",
    ): List<LuaValue>

    /** Call a function; errors are thrown as [LuaError]. */
    public fun call(
        function: LuaFunction,
        arguments: List<LuaValue> = emptyList(),
    ): List<LuaValue>

    /** Protected call (like `pcall`): errors land in the result instead of being thrown. */
    public fun pcall(
        function: LuaFunction,
        arguments: List<LuaValue> = emptyList(),
    ): Result<List<LuaValue>>

    /**
     * Runs [function] on a managed thread and returns a handle to pause, stop, or
     * await it. Execution is serialized with the rest of the runtime, so the handle
     * and other runtime calls are safe to use from multiple threads.
     */
    public fun start(
        function: LuaFunction,
        arguments: List<LuaValue> = emptyList(),
    ): ExecutionHandle

    public fun newTable(): LuaTable

    public fun newUserdata(
        instance: Any,
        metatable: LuaTable? = null,
    ): LuaUserdata

    public fun newCoroutine(body: LuaFunction): LuaCoroutine

    /** Attaches (or detaches, with null) a host debugger. Replaces any previous one. */
    public fun attachDebugger(debugger: Debugger?)

    /** Registers a breakpoint at [source]:[line], optionally guarded by a Lua [condition]. */
    public fun addBreakpoint(
        source: String,
        line: Int,
        condition: String? = null,
    ): Breakpoint

    /** Removes a previously added [breakpoint]. */
    public fun removeBreakpoint(breakpoint: Breakpoint)

    /** A snapshot of the active Lua call stack on the calling thread, innermost first. */
    public fun stackTrace(): List<DebugFrame>

    override fun close()

    public companion object {
        public fun create(config: LuaRuntimeConfig = LuaRuntimeConfig()): LuaRuntime = LuaRuntimeImpl(config)
    }
}
