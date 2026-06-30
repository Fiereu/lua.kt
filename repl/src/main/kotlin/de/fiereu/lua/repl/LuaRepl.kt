package de.fiereu.lua.repl

import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaRuntimeConfig
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaValue
import de.fiereu.lua.StdLibs
import de.fiereu.lua.get

/**
 * The interactive evaluation engine behind the shell, decoupled from stdin/stdout
 * so it can be driven by a test. Output (from `print`, `io.write`, and printed
 * expression results) flows to the injected [out] sink.
 */
class LuaRepl(
    private val out: (String) -> Unit,
) {
    enum class Step { CONTINUE, DONE }

    private val runtime =
        LuaRuntime.create(
            LuaRuntimeConfig(
                libraries = StdLibs.ALL,
                standardOutput = { bytes -> out(bytes.utf8()) },
            ),
        )

    private val pending = StringBuilder()

    /** True while a multi-line chunk is still being collected. */
    val isContinuing: Boolean
        get() = pending.isNotEmpty()

    /**
     * Feeds one input line. Returns [Step.CONTINUE] when the chunk is incomplete
     * and more input is needed, or [Step.DONE] when it was executed or rejected.
     */
    fun offer(line: String): Step {
        if (pending.isNotEmpty()) pending.append('\n')
        pending.append(line)
        val step = evaluate(pending.toString())
        if (step == Step.DONE) pending.setLength(0)
        return step
    }

    private fun evaluate(source: String): Step {
        val trimmed = source.trimStart()
        if (trimmed.startsWith("=")) {
            return compileAndRun("return " + trimmed.substring(1), printResults = true)
        }
        val expression = compileOrNull("return $source")
        if (expression != null) {
            execute(expression, printResults = true)
            return Step.DONE
        }
        return compileAndRun(source, printResults = false)
    }

    private fun compileAndRun(
        code: String,
        printResults: Boolean,
    ): Step {
        val chunk =
            try {
                runtime.load(code, CHUNK_NAME)
            } catch (error: LuaError) {
                if (isIncomplete(error)) return Step.CONTINUE
                out("lua: ${error.message}\n")
                return Step.DONE
            }
        execute(chunk, printResults)
        return Step.DONE
    }

    private fun compileOrNull(code: String): LuaFunction? =
        try {
            runtime.load(code, CHUNK_NAME)
        } catch (error: LuaError) {
            null
        }

    private fun execute(
        chunk: LuaFunction,
        printResults: Boolean,
    ) {
        val results =
            try {
                runtime.call(chunk)
            } catch (error: LuaError) {
                out("lua: ${error.message}\n")
                return
            }
        if (printResults && results.isNotEmpty()) printValues(results)
    }

    private fun printValues(results: List<LuaValue>) {
        val printer = runtime.globals[LuaString.of("print")]
        if (printer is LuaFunction) printer.call(results)
    }

    private fun isIncomplete(error: LuaError): Boolean {
        val message = error.message ?: return false
        return message.contains("<eof>") || message.contains("unfinished")
    }

    private companion object {
        const val CHUNK_NAME = "=stdin"
    }
}
