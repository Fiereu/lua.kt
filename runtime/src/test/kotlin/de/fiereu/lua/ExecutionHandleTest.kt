package de.fiereu.lua

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExecutionHandleTest :
    StringSpec({
        fun newRuntime(): LuaRuntime = LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL))

        fun start(
            runtime: LuaRuntime,
            source: String,
        ): ExecutionHandle = runtime.start(runtime.load(source, "@test"))

        fun waitForStatus(
            handle: ExecutionHandle,
            status: ExecutionStatus,
            timeoutMillis: Long = 2000,
        ): Boolean {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                if (handle.status == status) return true
                Thread.sleep(5)
            }
            return handle.status == status
        }

        "await returns the results of a completed script" {
            start(newRuntime(), "return 1 + 2, 'ok'").await() shouldBe listOf(LuaInteger(3), LuaString.of("ok"))
        }

        "await rethrows the error of a failed script" {
            shouldThrow<LuaError> { start(newRuntime(), "error('boom')").await() }
        }

        "stop aborts a tight loop" {
            val handle = start(newRuntime(), "local n = 0 while true do n = n + 1 end")
            handle.stop()
            handle.await(2000) shouldBe true
            handle.status shouldBe ExecutionStatus.STOPPED
        }

        "stop aborts an empty-bodied loop" {
            val handle = start(newRuntime(), "while true do end")
            handle.stop()
            handle.await(2000) shouldBe true
            handle.status shouldBe ExecutionStatus.STOPPED
        }

        "stop cannot be swallowed by pcall" {
            val handle = start(newRuntime(), "pcall(function() while true do end end) return 'survived'")
            handle.stop()
            handle.await(2000) shouldBe true
            handle.status shouldBe ExecutionStatus.STOPPED
        }

        "pause parks the script and resume lets it finish" {
            val handle = start(newRuntime(), "local i = 0 while i < 500000 do i = i + 1 end return i")
            try {
                handle.pause()
                waitForStatus(handle, ExecutionStatus.PAUSED) shouldBe true
                handle.resume()
                handle.await(3000) shouldBe true
                handle.status shouldBe ExecutionStatus.COMPLETED
                handle.result shouldBe listOf(LuaInteger(500000))
            } finally {
                handle.stop()
            }
        }

        "a paused execution releases the lock so another can run" {
            val runtime = newRuntime()
            val looping = runtime.start(runtime.load("while true do end", "@loop"))
            try {
                looping.pause()
                waitForStatus(looping, ExecutionStatus.PAUSED) shouldBe true
                runtime.execute("return 1 + 1", "@other").first() shouldBe LuaInteger(2)
            } finally {
                looping.stop()
                looping.await(2000)
            }
        }
    })
