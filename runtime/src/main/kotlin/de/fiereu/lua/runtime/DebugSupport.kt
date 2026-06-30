package de.fiereu.lua.runtime

import de.fiereu.lua.Breakpoint
import de.fiereu.lua.DebugAction
import de.fiereu.lua.DebugFrame
import de.fiereu.lua.Debugger
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaValue
import de.fiereu.lua.isTruthy

/**
 * One active Lua call. [baseScope] is the function's outermost scope, so locals
 * live between [currentScope] and [baseScope] (inclusive) while everything above
 * [baseScope] is captured upvalues.
 */
internal class CallFrame(
    val closure: LuaClosure,
    val baseScope: Scope,
) {
    var currentLine: Int = closure.proto.position.line
    var currentScope: Scope = baseScope

    val chunkName: String? get() = closure.chunkName
    val functionName: String? get() = closure.name

    private fun scopesInnerToOuter(): List<Scope> {
        val result = ArrayList<Scope>()
        var scope: Scope? = currentScope
        while (scope != null) {
            result.add(scope)
            if (scope === baseScope) break
            scope = scope.enclosing
        }
        return result
    }

    /** Locals in declaration order, outermost scope first. Later entries shadow earlier same-named ones. */
    fun orderedLocals(): List<Pair<String, Cell>> = scopesInnerToOuter().asReversed().flatMap { it.localCells() }

    /** Visible locals, innermost shadowing outermost, in a stable map. */
    fun visibleLocals(): LinkedHashMap<String, Cell> {
        val map = LinkedHashMap<String, Cell>()
        for ((name, cell) in scopesInnerToOuter().flatMap { it.localCells() }) {
            map.putIfAbsent(name, cell)
        }
        return map
    }

    /** Upvalues captured by this function: every cell above [baseScope]. */
    fun upvalueCells(): LinkedHashMap<String, Cell> {
        val map = LinkedHashMap<String, Cell>()
        var scope: Scope? = closure.captured
        while (scope != null) {
            for ((name, cell) in scope.localCells()) map.putIfAbsent(name, cell)
            scope = scope.enclosing
        }
        return map
    }
}

/** The public [DebugFrame] view over a [CallFrame], valid only during a callback. */
internal class FrameView(
    private val interpreter: Interpreter,
    private val frame: CallFrame,
    override val level: Int,
) : DebugFrame {
    override val source: String? get() = frame.chunkName
    override val currentLine: Int get() = frame.currentLine
    override val functionName: String? get() = frame.functionName
    override val what: String get() = if (frame.closure.isMain) "main" else "Lua"
    override val function: LuaFunction get() = frame.closure

    override fun locals(): Map<String, LuaValue> {
        val out = LinkedHashMap<String, LuaValue>()
        for ((name, cell) in frame.visibleLocals()) out[name] = cell.value
        return out
    }

    override fun getLocal(name: String): LuaValue? = frame.visibleLocals()[name]?.value

    override fun setLocal(
        name: String,
        value: LuaValue,
    ): Boolean {
        val cell = frame.visibleLocals()[name] ?: return false
        if (cell.readOnly) return false
        cell.value = value
        return true
    }

    override fun upvalues(): Map<String, LuaValue> {
        val out = LinkedHashMap<String, LuaValue>()
        for ((name, cell) in frame.upvalueCells()) out[name] = cell.value
        return out
    }

    override fun eval(code: String): List<LuaValue> = interpreter.evalInScope(code, frame.currentScope)
}

private enum class StepMode { NONE, INTO, OVER, OUT }

private class StepState {
    var mode = StepMode.NONE
    var targetDepth = 0
    var lastLine = -1
    var lastFrame: CallFrame? = null
    var instructions = 0L
    var active = false
}

private class ProfileEntry {
    var calls = 0L
    var totalNanos = 0L
}

/**
 * Drives all runtime debugging: the Lua-level hook, host [Debugger], breakpoints,
 * stepping, and profiling. The interpreter holds one of these only once something
 * is attached, so an undebugged program never reaches this class.
 */
internal class DebugController(
    private val interpreter: Interpreter,
) {
    var hostDebugger: Debugger? = null

    private var luaHook: LuaFunction? = null
    private var hookMask = ""
    private var hookCount = 0

    private var nextBreakpointId = 1
    private val breakpoints = LinkedHashMap<Int, Breakpoint>()

    private val stepState = ThreadLocal.withInitial { StepState() }

    private var profiling = false
    private val profile = LinkedHashMap<String, ProfileEntry>()
    private val timers = ThreadLocal.withInitial { ArrayDeque<Long>() }

    private fun <T> guarded(
        state: StepState,
        block: () -> T,
    ): T {
        state.active = true
        try {
            return block()
        } finally {
            state.active = false
        }
    }

    fun onStatement(frame: CallFrame) {
        val state = stepState.get()
        if (state.active) return
        state.instructions++
        val line = frame.currentLine
        val newLine = line != state.lastLine || frame !== state.lastFrame
        state.lastLine = line
        state.lastFrame = frame
        fireLuaHook(state, newLine, line)
        if (!newLine) return
        // A breakpoint that stops here may set a step mode, so do not also step on this same line.
        if (breakpoints.isNotEmpty() && handleBreakpoints(state, frame, line)) return
        if (state.mode != StepMode.NONE) handleStepping(state, frame)
    }

    fun onCall(frame: CallFrame) {
        val state = stepState.get()
        if (state.active) return
        if (profiling) {
            timers.get().addLast(System.nanoTime())
            profileEntry(frame).calls++
        }
        val hook = luaHook
        if (hook != null && hookMask.contains('c')) guarded(state) { callHook(hook, "call", -1) }
        hostDebugger?.let { host -> guarded(state) { host.onCall(FrameView(interpreter, frame, 1)) } }
    }

    fun onReturn(frame: CallFrame) {
        val state = stepState.get()
        if (state.active) return
        if (profiling) {
            val started = timers.get().removeLastOrNull()
            if (started != null) profileEntry(frame).totalNanos += System.nanoTime() - started
        }
        val hook = luaHook
        if (hook != null && hookMask.contains('r')) guarded(state) { callHook(hook, "return", -1) }
        hostDebugger?.let { host -> guarded(state) { host.onReturn(FrameView(interpreter, frame, 1)) } }
    }

    fun onError(
        error: LuaError,
        frame: CallFrame,
    ) {
        val host = hostDebugger ?: return
        val state = stepState.get()
        if (state.active) return
        guarded(state) { host.onError(error, FrameView(interpreter, frame, 1)) }
    }

    private fun fireLuaHook(
        state: StepState,
        newLine: Boolean,
        line: Int,
    ) {
        val hook = luaHook ?: return
        if (hookCount > 0 && state.instructions % hookCount == 0L) guarded(state) { callHook(hook, "count", -1) }
        if (newLine && hookMask.contains('l')) guarded(state) { callHook(hook, "line", line) }
    }

    private fun callHook(
        hook: LuaFunction,
        event: String,
        line: Int,
    ) {
        val lineArg = if (line >= 0) LuaInteger(line.toLong()) else LuaNil
        interpreter.call(hook, listOf(LuaString.of(event), lineArg))
    }

    private fun handleBreakpoints(
        state: StepState,
        frame: CallFrame,
        line: Int,
    ): Boolean {
        var delivered = false
        for (breakpoint in breakpoints.values.toList()) {
            if (!breakpoint.enabled || breakpoint.line != line) continue
            if (!matchesSource(breakpoint.source, frame.chunkName)) continue
            val condition = breakpoint.condition
            if (condition != null && !conditionHolds(state, frame, condition)) continue
            if (deliverBreakpoint(state, frame, breakpoint)) delivered = true
        }
        return delivered
    }

    private fun deliverBreakpoint(
        state: StepState,
        frame: CallFrame,
        breakpoint: Breakpoint,
    ): Boolean {
        val host = hostDebugger
        if (host != null) {
            val action = guarded(state) { host.onBreakpoint(FrameView(interpreter, frame, 1), breakpoint) }
            applyAction(state, action)
            return true
        }
        val hook = luaHook
        if (hook != null) guarded(state) { callHook(hook, "breakpoint", frame.currentLine) }
        return false
    }

    private fun handleStepping(
        state: StepState,
        frame: CallFrame,
    ) {
        val depth = interpreter.currentDepth()
        val stop =
            when (state.mode) {
                StepMode.INTO -> true
                StepMode.OVER -> depth <= state.targetDepth
                StepMode.OUT -> depth < state.targetDepth
                StepMode.NONE -> false
            }
        if (!stop) return
        val host = hostDebugger
        if (host == null) {
            state.mode = StepMode.NONE
            return
        }
        val action = guarded(state) { host.onLine(FrameView(interpreter, frame, 1)) }
        applyAction(state, action)
    }

    private fun applyAction(
        state: StepState,
        action: DebugAction,
    ) {
        when (action) {
            DebugAction.CONTINUE -> {
                state.mode = StepMode.NONE
            }

            DebugAction.STEP_INTO -> {
                state.mode = StepMode.INTO
            }

            DebugAction.STEP_OVER -> {
                state.mode = StepMode.OVER
                state.targetDepth = interpreter.currentDepth()
            }

            DebugAction.STEP_OUT -> {
                state.mode = StepMode.OUT
                state.targetDepth = interpreter.currentDepth()
            }

            DebugAction.STOP -> {
                throw LuaError.of("execution stopped by debugger")
            }
        }
    }

    private fun conditionHolds(
        state: StepState,
        frame: CallFrame,
        condition: String,
    ): Boolean =
        guarded(state) {
            try {
                interpreter.evalInScope(condition, frame.currentScope).firstOrNull()?.isTruthy ?: false
            } catch (ignored: LuaError) {
                false
            }
        }

    private fun profileEntry(frame: CallFrame): ProfileEntry = profile.getOrPut(profileKey(frame)) { ProfileEntry() }

    private fun profileKey(frame: CallFrame): String =
        frame.functionName ?: "function <${frame.chunkName ?: "?"}:${frame.closure.proto.position.line}>"

    fun setHook(
        hook: LuaFunction?,
        mask: String,
        count: Int,
    ) {
        luaHook = hook
        hookMask = mask
        hookCount = count
    }

    fun hook(): LuaFunction? = luaHook

    fun hookMask(): String = hookMask

    fun hookCount(): Int = hookCount

    fun addBreakpoint(
        source: String,
        line: Int,
        condition: String?,
    ): Breakpoint {
        val breakpoint = Breakpoint(nextBreakpointId++, source, line, condition)
        breakpoints[breakpoint.id] = breakpoint
        return breakpoint
    }

    fun removeBreakpoint(id: Int): Boolean = breakpoints.remove(id) != null

    fun breakpointList(): List<Breakpoint> = breakpoints.values.toList()

    fun startProfiling() {
        profiling = true
    }

    fun stopProfiling() {
        profiling = false
    }

    fun resetProfiling() {
        profile.clear()
    }

    /** Snapshot of profiling data as (key, calls, totalNanos), most-called first. */
    fun profileReport(): List<Triple<String, Long, Long>> =
        profile.entries
            .map { Triple(it.key, it.value.calls, it.value.totalNanos) }
            .sortedByDescending { it.second }

    private companion object {
        fun matchesSource(
            wanted: String,
            actual: String?,
        ): Boolean {
            if (actual == null) return false
            if (wanted == actual) return true
            val stripped = if (actual.startsWith("@") || actual.startsWith("=")) actual.substring(1) else actual
            return wanted == stripped
        }
    }
}
