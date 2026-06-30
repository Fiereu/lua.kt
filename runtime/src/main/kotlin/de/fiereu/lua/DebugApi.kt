package de.fiereu.lua

/**
 * What the runtime should do after a [Debugger] callback returns. The runtime
 * keeps executing until the next event that the chosen mode stops on.
 */
public enum class DebugAction {
    /** Resume freely. Only breakpoints will stop execution again. */
    CONTINUE,

    /** Stop at the next executed line, descending into called functions. */
    STEP_INTO,

    /** Stop at the next line in the same function or a caller, skipping over calls. */
    STEP_OVER,

    /** Stop at the next line after the current function returns. */
    STEP_OUT,

    /** Abort execution by raising an error in the running program. */
    STOP,
}

/**
 * A live, read-and-write view of one active Lua call frame. Instances are only
 * valid for the duration of the [Debugger] callback they are passed to. The frame
 * is gone once execution moves on.
 */
public interface DebugFrame {
    /** Stack level: 1 is the innermost running Lua function, growing toward callers. */
    public val level: Int

    /** The chunk name this frame runs (as given to `load`), or null. */
    public val source: String?

    /** The line currently executing in this frame. */
    public val currentLine: Int

    /** A name for the function if one is known (for example a `local function`), else null. */
    public val functionName: String?

    /** `"main"` for a loaded chunk, otherwise `"Lua"`. */
    public val what: String

    /** The function running in this frame. */
    public val function: LuaFunction

    /** All locals visible at the current point, in declaration order (inner shadowing outer). */
    public fun locals(): Map<String, LuaValue>

    /** Reads one local by name, or null if no such local is in scope. */
    public fun getLocal(name: String): LuaValue?

    /** Writes a local by name. Returns false if it is not in scope or is a `const`. */
    public fun setLocal(
        name: String,
        value: LuaValue,
    ): Boolean

    /** The upvalues captured by this frame's function, by name. */
    public fun upvalues(): Map<String, LuaValue>

    /** Evaluates Lua [code] as if written at this point, seeing this frame's locals and upvalues. */
    public fun eval(code: String): List<LuaValue>
}

/**
 * A source/line breakpoint. Obtain one from [LuaRuntime.addBreakpoint]. Toggle
 * [enabled] to disable it without removing it.
 */
public class Breakpoint internal constructor(
    /** A stable id for this breakpoint within its runtime. */
    public val id: Int,
    /** The chunk name to break in, matched against a frame's source. */
    public val source: String,
    /** The line to break on. */
    public val line: Int,
    /** An optional Lua expression. The breakpoint only fires when it is truthy. */
    public val condition: String?,
) {
    /** Whether this breakpoint is currently active. */
    public var enabled: Boolean = true
}

/**
 * Host-side debugger. Implement the callbacks you care about. Each returns a
 * [DebugAction] telling the runtime how to proceed. Callbacks run synchronously on
 * the thread executing Lua, so the program is paused for their duration.
 */
public interface Debugger {
    /** Called when execution stops on a line because of stepping. */
    public fun onLine(frame: DebugFrame): DebugAction = DebugAction.CONTINUE

    /** Called when a [Breakpoint] is hit. */
    public fun onBreakpoint(
        frame: DebugFrame,
        breakpoint: Breakpoint,
    ): DebugAction = DebugAction.CONTINUE

    /** Called when a Lua function is entered. */
    public fun onCall(frame: DebugFrame) {
        // No-op by default. Override to observe calls.
    }

    /** Called when a Lua function is about to return or unwind. */
    public fun onReturn(frame: DebugFrame) {
        // No-op by default. Override to observe returns.
    }

    /** Called when an error propagates out of a frame. The frame may be null if none is active. */
    public fun onError(
        error: LuaError,
        frame: DebugFrame?,
    ) {
        // No-op by default. Override to observe errors.
    }
}
