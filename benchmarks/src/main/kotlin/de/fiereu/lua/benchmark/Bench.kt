package de.fiereu.lua.benchmark

import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaRuntimeConfig
import de.fiereu.lua.StdLibs
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * A small profiling harness comparing three ways of running the same Lua workload:
 * the reference `lua` interpreter (if on PATH), lua.kt on a fresh runtime each run,
 * and lua.kt on a single runtime that has been warmed up. Run it with
 * `./gradlew :benchmarks:profile` (add `-Plua=/path/to/lua` to point the reference
 * leg at a specific binary).
 */

private class Workload(
    val name: String,
    val source: String,
)

private val WORKLOADS =
    listOf(
        Workload(
            "fib(25)",
            """
            local function fib(n)
                if n < 2 then return n end
                return fib(n - 1) + fib(n - 2)
            end
            return fib(25)
            """.trimIndent(),
        ),
        Workload(
            "sum-loop 1e6",
            """
            local total = 0
            for i = 1, 1000000 do total = total + i end
            return total
            """.trimIndent(),
        ),
        Workload(
            "string-concat 10k",
            """
            local parts = {}
            for i = 1, 10000 do parts[i] = tostring(i) end
            return #table.concat(parts, ",")
            """.trimIndent(),
        ),
    )

private const val WARMUP = 20
private const val MEASURE = 20
private const val FRESH_WARMUP = 3
private const val FRESH_RUNS = 10
private const val REFERENCE_RUNS = 5

fun main() {
    val lua = findLua()
    println("lua.kt profiling harness")
    println(if (lua != null) "reference lua: $lua" else "reference lua: not found on PATH (skipping that leg)")
    println()
    printHeader()
    for (workload in WORKLOADS) {
        val reference = if (lua != null) profileReference(lua, workload) else Double.NaN
        val fresh = profileFresh(workload.source)
        val warmed = profileWarmed(workload.source)
        printRow(workload.name, reference, fresh, warmed)
    }
}

private fun findLua(): String? {
    System.getProperty("lua.bin")?.let { return it }
    return listOf("lua", "lua5.5", "lua5.4", "luajit").firstOrNull { canRun(it) }
}

private fun canRun(command: String): Boolean =
    try {
        ProcessBuilder(command, "-v")
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes() }
            .waitFor() == 0
    } catch (ignored: IOException) {
        false
    }

private fun profileReference(
    command: String,
    workload: Workload,
): Double {
    val script =
        File.createTempFile("bench", ".lua").apply {
            writeText(workload.source)
            deleteOnExit()
        }
    val samples = ArrayList<Double>()
    repeat(REFERENCE_RUNS) {
        val start = System.nanoTime()
        val process = ProcessBuilder(command, script.absolutePath).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        if (process.waitFor() == 0) samples.add(millisSince(start))
    }
    return median(samples)
}

private fun profileFresh(source: String): Double {
    repeat(FRESH_WARMUP) { runFresh(source) }
    val samples = ArrayList<Double>()
    repeat(FRESH_RUNS) {
        val start = System.nanoTime()
        runFresh(source)
        samples.add(millisSince(start))
    }
    return median(samples)
}

private fun runFresh(source: String) {
    LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL)).use { it.execute(source, "=bench") }
}

private fun profileWarmed(source: String): Double {
    LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL)).use { runtime ->
        val chunk = runtime.load(source, "=bench")
        repeat(WARMUP) { runtime.call(chunk) }
        val samples = ArrayList<Double>()
        repeat(MEASURE) {
            val start = System.nanoTime()
            runtime.call(chunk)
            samples.add(millisSince(start))
        }
        return median(samples)
    }
}

private fun millisSince(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

private fun median(samples: List<Double>): Double {
    if (samples.isEmpty()) return Double.NaN
    val sorted = samples.sorted()
    return sorted[sorted.size / 2]
}

private fun printHeader() {
    println(String.format(Locale.ROOT, "%-20s %16s %16s %16s", "workload", "reference lua", "lua.kt (fresh)", "lua.kt (warmed)"))
}

private fun printRow(
    name: String,
    reference: Double,
    fresh: Double,
    warmed: Double,
) {
    println(String.format(Locale.ROOT, "%-20s %16s %16s %16s", name, format(reference), format(fresh), format(warmed)))
}

private fun format(millis: Double): String = if (millis.isNaN()) "SKIPPED" else String.format(Locale.ROOT, "%.3f ms", millis)
