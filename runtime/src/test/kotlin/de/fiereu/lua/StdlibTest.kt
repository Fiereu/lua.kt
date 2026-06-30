package de.fiereu.lua

import de.fiereu.lua.common.LuaBytes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StdlibTest :
    StringSpec({
        fun run(source: String): List<LuaValue> = LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL)).execute(source)

        fun one(source: String): LuaValue = run(source).first()

        fun captureOutput(source: String): String {
            val output = StringBuilder()
            val config = LuaRuntimeConfig(libraries = StdLibs.ALL, standardOutput = { bytes -> output.append(bytes.utf8()) })
            LuaRuntime.create(config).execute(source)
            return output.toString()
        }

        "print writes tab-separated values with a newline" {
            captureOutput("print('hello', 1, true)") shouldBe "hello\t1\ttrue\n"
        }

        "type and tostring report Lua types" {
            run("return type(1), type('a'), type({}), type(print)") shouldBe
                listOf(LuaString.of("number"), LuaString.of("string"), LuaString.of("table"), LuaString.of("function"))
        }

        "tonumber parses strings and bases" {
            run("return tonumber('42'), tonumber('ff', 16), tonumber('x')") shouldBe
                listOf(LuaInteger(42), LuaInteger(255), LuaNil)
        }

        "string methods are callable with colon syntax" {
            one("return ('abc'):upper()") shouldBe LuaString.of("ABC")
        }

        "string.format mirrors printf" {
            one("return string.format('%d-%5.2f-%s', 7, 3.14159, 'hi')") shouldBe LuaString.of("7- 3.14-hi")
        }

        "string.sub respects negative indices" {
            run("return ('hello'):sub(2, 4), ('hello'):sub(-3)") shouldBe
                listOf(LuaString.of("ell"), LuaString.of("llo"))
        }

        "string.find and match work with patterns" {
            run("return string.find('hello world', 'o')") shouldBe listOf(LuaInteger(5), LuaInteger(5))
            one("return string.match('key=value', '(%w+)=(%w+)')") shouldBe LuaString.of("key")
            one("return (string.gsub('hello', 'l', 'L'))") shouldBe LuaString.of("heLLo")
        }

        "string.gmatch iterates matches" {
            one(
                """
                local words = {}
                for w in string.gmatch('a,bb,ccc', '[^,]+') do words[#words + 1] = w end
                return table.concat(words, '-')
                """.trimIndent(),
            ) shouldBe LuaString.of("a-bb-ccc")
        }

        "string.byte and char round-trip" {
            run("return string.char(72, 105), string.byte('Hi', 1, 2)") shouldBe
                listOf(LuaString.of("Hi"), LuaInteger(72), LuaInteger(105))
        }

        "table insert, remove, and length" {
            one(
                """
                local t = {}
                table.insert(t, 'a')
                table.insert(t, 'b')
                table.insert(t, 1, 'first')
                table.remove(t, 2)
                return table.concat(t, ',')
                """.trimIndent(),
            ) shouldBe LuaString.of("first,b")
        }

        "table.sort orders elements" {
            one(
                """
                local t = {3, 1, 2}
                table.sort(t)
                return table.concat(t, ',')
                """.trimIndent(),
            ) shouldBe LuaString.of("1,2,3")
        }

        "table.pack and unpack are inverse" {
            run("local t = table.pack(1, 2, 3) return t.n, table.unpack(t)") shouldBe
                listOf(LuaInteger(3), LuaInteger(1), LuaInteger(2), LuaInteger(3))
        }

        "math functions behave" {
            run("return math.floor(3.7), math.max(1, 5, 3), math.type(1), math.type(1.0)") shouldBe
                listOf(LuaInteger(3), LuaInteger(5), LuaString.of("integer"), LuaString.of("float"))
        }

        "pairs visits every entry" {
            one(
                """
                local t = {10, 20, 30}
                local sum = 0
                for _, v in pairs(t) do sum = sum + v end
                return sum
                """.trimIndent(),
            ) shouldBe LuaInteger(60)
        }

        "pcall reports success and failure" {
            run("return pcall(function() return 1 end)") shouldBe listOf(LuaBoolean.TRUE, LuaInteger(1))
            (run("return pcall(function() error('boom') end)")[0]) shouldBe LuaBoolean.FALSE
        }

        "setmetatable installs an __index fallback" {
            one(
                """
                local base = {greet = 'hi'}
                local t = setmetatable({}, {__index = base})
                return t.greet
                """.trimIndent(),
            ) shouldBe LuaString.of("hi")
        }

        "coroutines produce a sequence" {
            one(
                """
                local co = coroutine.create(function(a)
                  for i = 1, 3 do coroutine.yield(a + i) end
                  return 'done'
                end)
                local _, first = coroutine.resume(co, 10)
                local _, second = coroutine.resume(co)
                return first + second
                """.trimIndent(),
            ) shouldBe LuaInteger(23)
        }

        "coroutine.wrap yields values directly" {
            one(
                """
                local gen = coroutine.wrap(function()
                  for i = 1, 3 do coroutine.yield(i) end
                end)
                return gen() + gen() + gen()
                """.trimIndent(),
            ) shouldBe LuaInteger(6)
        }

        "_VERSION is set" {
            one("return _VERSION") shouldBe LuaString(LuaBytes.of("Lua 5.5"))
        }

        "load compiles a chunk from a string" {
            one("local f = load('return 1 + 2') return f()") shouldBe LuaInteger(3)
            (run("local f, err = load('return +') return f")[0]) shouldBe LuaNil
        }

        "require returns a loaded library" {
            one("return require('math') == math") shouldBe LuaBoolean.TRUE
        }

        "collectgarbage is a no-op returning zero" {
            one("return collectgarbage()") shouldBe LuaInteger(0)
        }

        "utf8 encodes and decodes" {
            one("return utf8.char(72, 0x20AC)") shouldBe LuaString.of("H€")
            run("return utf8.len('héllo'), utf8.codepoint('A')") shouldBe
                listOf(LuaInteger(5), LuaInteger(65))
        }

        "string.pack round-trips integers and strings" {
            one("return (string.unpack('>i4', string.pack('>i4', 1000)))") shouldBe LuaInteger(1000)
            one("return string.packsize('i4i8')") shouldBe LuaInteger(12)
        }

        "math gains deg and rad" {
            one("return math.deg(math.pi)") shouldBe LuaFloat(180.0)
        }

        "table.create returns a usable table" {
            one("local t = table.create(8) t[1] = 'x' return t[1]") shouldBe LuaString.of("x")
        }

        "os.date formats a fixed timestamp in UTC" {
            one("return os.date('!%Y-%m-%d', 0)") shouldBe LuaString.of("1970-01-01")
        }

        "debug.setmetatable can attach a metatable to a number type" {
            one(
                """
                debug.setmetatable(0, {__index = {abs = function(self) return self < 0 and -self or self end}})
                return (-5):abs()
                """.trimIndent(),
            ) shouldBe LuaInteger(5)
        }

        "io.write goes to the configured sink" {
            captureOutput("io.write('a', 1, 'b')") shouldBe "a1b"
        }

        "require returns the same module each call" {
            one("return require('string') == require('string')") shouldBe LuaBoolean.TRUE
        }
    })
