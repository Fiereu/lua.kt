package de.fiereu.lua

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class DebugExTest :
    StringSpec({
        fun newRuntime(): LuaRuntime = LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL))

        fun run(source: String): List<LuaValue> = newRuntime().execute(source)

        fun one(source: String): LuaValue = run(source).first()

        "errors carry a populated traceback" {
            val runtime = newRuntime()
            val chunk =
                runtime.load(
                    """
                    local function a() error("boom") end
                    local function b() a() end
                    b()
                    """.trimIndent(),
                    "@t.lua",
                )
            val result = runtime.pcall(chunk)
            result.isFailure shouldBe true
            val error = result.exceptionOrNull() as LuaError
            error.traceback shouldNotBe null
            error.traceback!! shouldContain "stack traceback:"
            error.traceback!! shouldContain "function 'a'"
        }

        "debugEx.traceback names the active Lua frames" {
            val text =
                (
                    one(
                        """
                        local function f() return debugEx.traceback() end
                        return f()
                        """.trimIndent(),
                    ) as LuaString
                ).bytes.utf8()
            text shouldContain "stack traceback:"
            text shouldContain "function 'f'"
        }

        "getinfo reports source, params and varargs" {
            run(
                """
                local function greet(a, b) return debugEx.getinfo(1) end
                local info = greet(1, 2)
                return info.what, info.nparams, info.isvararg, info.name
                """.trimIndent(),
            ) shouldBe listOf(LuaString.of("Lua"), LuaInteger(2), LuaBoolean.FALSE, LuaString.of("greet"))
        }

        "getlocal and setlocal read and write an active frame" {
            one(
                """
                local function f()
                    local x = 10
                    debugEx.setlocal(1, 1, 99)
                    return x
                end
                return f()
                """.trimIndent(),
            ) shouldBe LuaInteger(99)
        }

        "setlocal refuses a const loop variable" {
            one(
                """
                local function f()
                    for i = 1, 1 do
                        debugEx.setlocal(1, 1, 99)
                        return i
                    end
                end
                return f()
                """.trimIndent(),
            ) shouldBe LuaInteger(1)
        }

        "upvalues exposes captured variables" {
            one(
                """
                local up = 5
                local function f() return up end
                return debugEx.upvalues(f).up
                """.trimIndent(),
            ) shouldBe LuaInteger(5)
        }

        "sethook fires on each new line" {
            val count =
                (
                    one(
                        """
                        local n = 0
                        debugEx.sethook(function() n = n + 1 end, "l")
                        local a = 1
                        local b = 2
                        local c = 3
                        debugEx.sethook()
                        return n
                        """.trimIndent(),
                    ) as LuaInteger
                ).value
            (count >= 3) shouldBe true
        }

        "eval runs in the scope of a frame" {
            one(
                """
                local function f()
                    local a = 10
                    local b = 20
                    return debugEx.eval(1, "a + b")
                end
                return f()
                """.trimIndent(),
            ) shouldBe LuaInteger(30)
        }

        "the profiler counts calls" {
            run(
                """
                debugEx.profile.start()
                local function work() return 1 end
                for i = 1, 5 do work() end
                debugEx.profile.stop()
                local report = debugEx.profile.report()
                return #report, report[1].calls
                """.trimIndent(),
            ) shouldBe listOf(LuaInteger(1), LuaInteger(5))
        }

        "a host debugger hits a breakpoint and inspects locals" {
            val hits = mutableListOf<Int>()
            var seenX: LuaValue? = null
            val runtime = newRuntime()
            runtime.addBreakpoint("@bp.lua", 3)
            runtime.attachDebugger(
                object : Debugger {
                    override fun onBreakpoint(
                        frame: DebugFrame,
                        breakpoint: Breakpoint,
                    ): DebugAction {
                        hits.add(frame.currentLine)
                        seenX = frame.getLocal("x")
                        return DebugAction.CONTINUE
                    }
                },
            )
            runtime.execute(
                """
                local x = 41
                x = x + 1
                local y = x
                return y
                """.trimIndent(),
                "@bp.lua",
            )
            hits shouldBe listOf(3)
            seenX shouldBe LuaInteger(42)
        }

        "a host debugger can step over lines" {
            val lines = mutableListOf<Int>()
            val runtime = newRuntime()
            runtime.addBreakpoint("@s.lua", 1)
            runtime.attachDebugger(
                object : Debugger {
                    override fun onBreakpoint(
                        frame: DebugFrame,
                        breakpoint: Breakpoint,
                    ): DebugAction = DebugAction.STEP_OVER

                    override fun onLine(frame: DebugFrame): DebugAction {
                        lines.add(frame.currentLine)
                        return if (lines.size < 3) DebugAction.STEP_OVER else DebugAction.CONTINUE
                    }
                },
            )
            runtime.execute(
                """
                local a = 1
                local b = 2
                local c = 3
                local d = 4
                return d
                """.trimIndent(),
                "@s.lua",
            )
            lines shouldBe listOf(2, 3, 4)
        }
    })
