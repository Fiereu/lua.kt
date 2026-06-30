package de.fiereu.lua

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class InterpreterTest :
    StringSpec({
        fun run(source: String): List<LuaValue> = LuaRuntime.create().execute(source)

        fun one(source: String): LuaValue = run(source).first()

        "evaluates arithmetic with precedence" {
            one("return 1 + 2 * 3") shouldBe LuaInteger(7)
        }

        "keeps integer and float subtypes distinct" {
            one("return 7 // 2") shouldBe LuaInteger(3)
            one("return 7 / 2") shouldBe LuaFloat(3.5)
            one("return 2 ^ 10") shouldBe LuaFloat(1024.0)
            one("return 7 % 3") shouldBe LuaInteger(1)
        }

        "performs bitwise operations on integers" {
            one("return 5 & 3") shouldBe LuaInteger(1)
            one("return 1 << 4") shouldBe LuaInteger(16)
            one("return ~0") shouldBe LuaInteger(-1)
        }

        "coerces numeric strings in arithmetic" {
            one("return '10' + 5") shouldBe LuaInteger(15)
        }

        "concatenates strings and numbers" {
            one("return 'a' .. 1 .. 2.0") shouldBe LuaString.of("a12.0")
        }

        "compares numbers across subtypes" {
            one("return 1 == 1.0") shouldBe LuaBoolean.TRUE
            one("return 1 < 2") shouldBe LuaBoolean.TRUE
            one("return 'a' < 'b'") shouldBe LuaBoolean.TRUE
        }

        "short-circuits logical operators" {
            one("return false and error('boom')") shouldBe LuaBoolean.FALSE
            one("return 1 or error('boom')") shouldBe LuaInteger(1)
        }

        "assigns and swaps locals" {
            run("local a, b = 1, 2 a, b = b, a return a, b") shouldBe listOf(LuaInteger(2), LuaInteger(1))
        }

        "captures upvalues in closures" {
            run(
                """
                local function counter()
                  local n = 0
                  return function() n = n + 1 return n end
                end
                local c = counter()
                return c(), c(), c()
                """.trimIndent(),
            ) shouldBe listOf(LuaInteger(1), LuaInteger(2), LuaInteger(3))
        }

        "passes and expands varargs" {
            run("local function f(...) return ... end return f(1, 2, 3)") shouldBe
                listOf(LuaInteger(1), LuaInteger(2), LuaInteger(3))
        }

        "truncates a parenthesized call to one value" {
            run("local function f() return 1, 2, 3 end return (f())") shouldBe listOf(LuaInteger(1))
        }

        "adjusts multiple assignment from a call" {
            run("local function f() return 1, 2, 3 end local a, b = f() return a, b") shouldBe
                listOf(LuaInteger(1), LuaInteger(2))
        }

        "runs a numeric for loop" {
            one("local s = 0 for i = 1, 10 do s = s + i end return s") shouldBe LuaInteger(55)
        }

        "runs a generic for loop" {
            one(
                """
                local function iter(t, i)
                  i = i + 1
                  if t[i] then return i, t[i] end
                end
                local t = {10, 20, 30}
                local s = 0
                for _, v in iter, t, 0 do s = s + v end
                return s
                """.trimIndent(),
            ) shouldBe LuaInteger(60)
        }

        "handles while loops and break" {
            one("local i = 0 while true do i = i + 1 if i == 5 then break end end return i") shouldBe LuaInteger(5)
        }

        "supports goto" {
            one("local i = 1 ::top:: i = i + 1 if i < 5 then goto top end return i") shouldBe LuaInteger(5)
        }

        "builds tables and reads the length border" {
            run("local t = {1, 2, 3, x = 10} return t[1], t[3], t.x, #t") shouldBe
                listOf(LuaInteger(1), LuaInteger(3), LuaInteger(10), LuaInteger(3))
        }

        "invokes the __add metamethod" {
            val runtime = LuaRuntime.create()
            val meta = runtime.newTable()
            meta["__add"] = LuaFunction { listOf(LuaInteger(99)) }
            val table = runtime.newTable().also { it.metatable = meta }
            runtime.globals["a"] = table
            runtime.globals["b"] = table
            runtime.call(runtime.load("return a + b")) shouldBe listOf(LuaInteger(99))
        }

        "invokes the __index metamethod" {
            val runtime = LuaRuntime.create()
            val meta = runtime.newTable()
            meta["__index"] = LuaFunction { listOf(LuaString.of("indexed")) }
            val table = runtime.newTable().also { it.metatable = meta }
            runtime.globals["t"] = table
            runtime.call(runtime.load("return t.missing")) shouldBe listOf(LuaString.of("indexed"))
        }

        "reports arithmetic errors through pcall" {
            val runtime = LuaRuntime.create()
            val result = runtime.pcall(runtime.load("return nil + 1"))
            result.isFailure shouldBe true
        }

        "rejects assignment to a const variable" {
            val runtime = LuaRuntime.create()
            val result = runtime.pcall(runtime.load("local x <const> = 1 x = 2"))
            result.isFailure shouldBe true
        }

        "rejects assignment to a for control variable" {
            val runtime = LuaRuntime.create()
            val result = runtime.pcall(runtime.load("for i = 1, 3 do i = 5 end"))
            result.isFailure shouldBe true
        }

        "drives a coroutine through resume and yield" {
            val runtime = LuaRuntime.create()
            val body =
                LuaFunction { args ->
                    de.fiereu.lua.runtime.CoroutineSupport
                        .yield(listOf(LuaInteger((args[0] as LuaInteger).value + 1)))
                }
            val coroutine = runtime.newCoroutine(body)
            coroutine.resume(listOf(LuaInteger(10))) shouldBe listOf(LuaInteger(11))
        }
    })
