package de.fiereu.lua.repl

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class LuaReplTest :
    StringSpec({
        fun session(): Pair<LuaRepl, StringBuilder> {
            val output = StringBuilder()
            return LuaRepl { text -> output.append(text) } to output
        }

        "auto-prints an expression" {
            val (repl, output) = session()
            repl.offer("1 + 1") shouldBe LuaRepl.Step.DONE
            output.toString() shouldBe "2\n"
        }

        "supports the = print shorthand" {
            val (repl, output) = session()
            repl.offer("=math.max(3, 7)") shouldBe LuaRepl.Step.DONE
            output.toString() shouldBe "7\n"
        }

        "keeps state across lines" {
            val (repl, output) = session()
            repl.offer("x = 41")
            repl.offer("=x + 1")
            output.toString() shouldBe "42\n"
        }

        "runs statements without printing" {
            val (repl, output) = session()
            repl.offer("local total = 0 for i = 1, 3 do total = total + i end print(total)")
            output.toString() shouldBe "6\n"
        }

        "continues a multi-line chunk" {
            val (repl, output) = session()
            repl.offer("function f()") shouldBe LuaRepl.Step.CONTINUE
            repl.isContinuing shouldBe true
            repl.offer("  return 7") shouldBe LuaRepl.Step.CONTINUE
            repl.offer("end") shouldBe LuaRepl.Step.DONE
            repl.isContinuing shouldBe false
            repl.offer("=f()") shouldBe LuaRepl.Step.DONE
            output.toString() shouldBe "7\n"
        }

        "recovers from an error and keeps working" {
            val (repl, output) = session()
            repl.offer("=nil + 1") shouldBe LuaRepl.Step.DONE
            output.toString() shouldContain "lua:"
            output.clear()
            repl.offer("=2 * 3") shouldBe LuaRepl.Step.DONE
            output.toString() shouldBe "6\n"
        }

        "exercises the full standard library" {
            val (repl, output) = session()
            repl.offer("=string.format('%d:%s', 1, utf8.char(65))")
            output.toString() shouldBe "1:A\n"
        }
    })
