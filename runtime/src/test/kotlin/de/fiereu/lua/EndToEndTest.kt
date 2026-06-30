package de.fiereu.lua

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EndToEndTest :
    StringSpec({
        fun output(source: String): String {
            val text = StringBuilder()
            val config = LuaRuntimeConfig(standardOutput = { bytes -> text.append(bytes.utf8()) })
            LuaRuntime.create(config).execute(source)
            return text.toString()
        }

        "runs a representative program end to end" {
            val program =
                """
                local function factorial(n)
                  if n <= 1 then return 1 end
                  return n * factorial(n - 1)
                end

                local Account = {}
                Account.__index = Account
                function Account.new(balance) return setmetatable({balance = balance}, Account) end
                function Account:deposit(amount) self.balance = self.balance + amount end

                local acc = Account.new(100)
                acc:deposit(50)

                local squares = {}
                for i = 1, 4 do squares[i] = i * i end

                print(string.format("5! = %d", factorial(5)))
                print("balance = " .. acc.balance)
                print(table.concat(squares, ","))

                local gen = coroutine.wrap(function()
                  for _, v in ipairs(squares) do coroutine.yield(v) end
                end)
                print(gen(), gen())
                """.trimIndent()
            output(program) shouldBe "5! = 120\nbalance = 150\n1,4,9,16\n1\t4\n"
        }
    })
