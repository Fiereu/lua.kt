package de.fiereu.lua.repl

import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaRuntimeConfig
import de.fiereu.lua.StdLibs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs the official Lua test files under `testes` through the runtime and prints a
 * pass/fail report. This is a coverage probe, not a pass/fail gate: the official
 * suite exercises libraries (io, os, debug) and an internal C test module beyond
 * this milestone, so many files are expected to fail today.
 */
fun main(args: Array<String>) {
    val directory = File(args.getOrElse(0) { "third_party/lua/testes" })
    val files = (directory.listFiles { file -> file.extension == "lua" } ?: emptyArray()).sortedBy { it.name }
    if (files.isEmpty()) {
        println("no .lua files found in ${directory.path}")
        return
    }
    var passed = 0
    var executed = 0
    for (file in files) {
        if (file.name in DRIVERS_AND_STRESS) continue
        executed++
        val outcome = runFile(file)
        println(outcome.line)
        if (outcome.passed) passed++
    }
    println()
    println("$passed/$executed test files ran without error under StdLibs.ALL")
}

private class Outcome(
    val passed: Boolean,
    val line: String,
)

private fun runFile(file: File): Outcome {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
    val future =
        executor.submit<Outcome> {
            try {
                val runtime = LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL, standardOutput = {}))
                runtime.call(runtime.load(file.readBytes(), "@${file.name}"))
                Outcome(true, "PASS ${file.name}")
            } catch (error: Exception) {
                Outcome(false, "FAIL ${file.name}: ${firstLine(error)}")
            }
        }
    return try {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (timeout: TimeoutException) {
        future.cancel(true)
        Outcome(false, "TIMEOUT ${file.name}")
    } catch (error: Exception) {
        Outcome(false, "FAIL ${file.name}: ${firstLine(error)}")
    } finally {
        executor.shutdownNow()
    }
}

private fun firstLine(error: Throwable): String {
    val message = error.message ?: error.javaClass.simpleName
    return message.lineSequence().first().take(140)
}

private const val TIMEOUT_SECONDS = 15L

private val DRIVERS_AND_STRESS =
    setOf(
        "all.lua",
        "main.lua",
        "heavy.lua",
        "big.lua",
        "verybig.lua",
        "memerr.lua",
        "cstack.lua",
        "gc.lua",
        "gengc.lua",
        "tracegc.lua",
    )
