package de.fiereu.lua.repl

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaRuntimeConfig
import de.fiereu.lua.LuaString
import de.fiereu.lua.StdLibs
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point for the demo app. With a script path it runs that file; with no
 * arguments it starts the interactive shell. This demonstrates the runtime, it
 * is not part of the library.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        repl()
        return
    }
    runFile(args[0], args.drop(1))
}

private fun runFile(
    path: String,
    scriptArgs: List<String>,
) {
    val runtime = LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL))
    try {
        val chunk = runtime.load(File(path).readBytes(), "@$path")
        runtime.call(chunk, scriptArgs.map { LuaString.of(it) })
    } catch (error: LuaError) {
        System.err.println("lua: ${error.message}")
        exitProcess(1)
    }
}

private fun repl() {
    println("lua.kt 0.1.0 (Lua 5.5)  --  Ctrl-D to exit")
    val repl = LuaRepl { text -> print(text) }
    while (true) {
        print(if (repl.isContinuing) ">> " else "> ")
        System.out.flush()
        val line = readlnOrNull() ?: break
        repl.offer(line)
    }
    println()
}
