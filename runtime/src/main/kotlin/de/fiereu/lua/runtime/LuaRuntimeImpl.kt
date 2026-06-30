package de.fiereu.lua.runtime

import de.fiereu.lua.LuaCoroutine
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaRuntimeConfig
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaUserdata
import de.fiereu.lua.LuaValue
import de.fiereu.lua.common.LuaSyntaxException
import de.fiereu.lua.parser.LuaParser
import de.fiereu.lua.parser.ast.Chunk
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The default [LuaRuntime], wiring the parser to the tree-walking interpreter. */
internal class LuaRuntimeImpl(
    val config: LuaRuntimeConfig,
) : LuaRuntime {
    private val globalTable = LuaTableImpl()
    val interpreter = Interpreter(globalTable, { LuaTableImpl() }, config.maxCallDepth)

    /** Serializes Lua execution so the runtime is safe to drive from multiple threads. */
    private val runLock = ReentrantLock()

    override val globals: LuaTable
        get() = globalTable

    init {
        config.libraries.forEach { it.install(this, globalTable) }
        config.debugger?.let { interpreter.debugController().hostDebugger = it }
    }

    override fun attachDebugger(debugger: de.fiereu.lua.Debugger?) {
        interpreter.debugController().hostDebugger = debugger
    }

    override fun addBreakpoint(
        source: String,
        line: Int,
        condition: String?,
    ): de.fiereu.lua.Breakpoint = interpreter.debugController().addBreakpoint(source, line, condition)

    override fun removeBreakpoint(breakpoint: de.fiereu.lua.Breakpoint) {
        interpreter.debugController().removeBreakpoint(breakpoint.id)
    }

    override fun stackTrace(): List<de.fiereu.lua.DebugFrame> {
        val stack = interpreter.callStack()
        return stack.indices.reversed().map { index ->
            FrameView(interpreter, stack[index], stack.size - index)
        }
    }

    override fun load(
        source: String,
        chunkName: String,
    ): LuaFunction = compile({ LuaParser.parse(source, chunkName) }, chunkName)

    override fun load(
        source: ByteArray,
        chunkName: String,
    ): LuaFunction = compile({ LuaParser.parse(source, chunkName) }, chunkName)

    private inline fun compile(
        parse: () -> Chunk,
        chunkName: String,
    ): LuaFunction {
        val chunk =
            try {
                parse()
            } catch (error: LuaSyntaxException) {
                throw LuaError.of(error.message ?: "syntax error")
            }
        return interpreter.loadChunk(chunk, chunkName)
    }

    override fun execute(
        source: String,
        chunkName: String,
    ): List<LuaValue> = call(load(source, chunkName))

    override fun call(
        function: LuaFunction,
        arguments: List<LuaValue>,
    ): List<LuaValue> = runLock.withLock { function.call(arguments) }

    override fun pcall(
        function: LuaFunction,
        arguments: List<LuaValue>,
    ): Result<List<LuaValue>> =
        runLock.withLock {
            try {
                Result.success(function.call(arguments))
            } catch (error: LuaError) {
                Result.failure(error)
            }
        }

    override fun start(
        function: LuaFunction,
        arguments: List<LuaValue>,
    ): de.fiereu.lua.ExecutionHandle = ManagedExecution(interpreter, runLock, function, arguments).also { it.start() }

    override fun newTable(): LuaTable = LuaTableImpl()

    override fun newUserdata(
        instance: Any,
        metatable: LuaTable?,
    ): LuaUserdata = LuaUserdataImpl(instance, metatable)

    override fun newCoroutine(body: LuaFunction): LuaCoroutine = LuaCoroutineImpl(body)

    override fun close() {
        // Resources tied to the runtime are released by the JVM. To-be-closed
        // values are handled at block scope, not here.
    }
}
